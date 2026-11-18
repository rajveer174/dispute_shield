package com.testpay.shield.session;

import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.domain.Dataset;
import com.testpay.shield.domain.Records.*;
import com.testpay.shield.ingest.CsvLoader;
import com.testpay.shield.ingest.FileKind;
import com.testpay.shield.ingest.SchemaException;
import com.testpay.shield.scoring.ModelStore;
import com.testpay.shield.scoring.Predictions.PaymentPrediction;
import com.testpay.shield.scoring.ScoringService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Creates, scores and expires upload sessions. */
@Service
@Profile("!train")
public class SessionService {

    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();
    private final ScoringService scoring;
    private final ModelStore store;
    private final ShieldProperties props;

    public SessionService(ScoringService scoring, ModelStore store, ShieldProperties props) {
        this.scoring = scoring;
        this.store = store;
        this.props = props;
    }

    /** Step 1: invoices + payments (+ photos, optional teams). Scores every payment. */
    public UploadSession createSession(MultipartFile invoices, MultipartFile payments,
                                       MultipartFile photos, MultipartFile teams) throws IOException {
        store.bundle(); // fail fast if models are not loaded
        List<String> problems = new ArrayList<>();
        if (isEmpty(invoices)) problems.add("invoices.csv is required in step 1");
        if (isEmpty(payments)) problems.add("payments.csv is required in step 1");
        if (!problems.isEmpty()) throw new SchemaException(problems);

        Dataset ds = new Dataset();
        UploadSession s = new UploadSession(UUID.randomUUID().toString());
        load(FileKind.INVOICES, invoices, ds, problems);
        load(FileKind.PAYMENTS, payments, ds, problems);
        if (!isEmpty(photos)) load(FileKind.PHOTOS, photos, ds, problems);
        else s.warnings.add("No photos.csv uploaded: photo counts are taken from the invoice rows.");
        if (!isEmpty(teams)) load(FileKind.TEAMS, teams, ds, problems);
        if (!problems.isEmpty()) throw new SchemaException(problems);
        if (ds.payments.isEmpty()) throw new SchemaException("payments.csv has no rows");

        s.fileRows.put("invoices", ds.invoices.size());
        s.fileRows.put("payments", ds.payments.size());
        s.fileRows.put("photos", ds.photoCount);
        if (!ds.teams.isEmpty()) s.fileRows.put("teams", ds.teams.size());

        Set<String> knownTeams = new HashSet<>(ds.teams.keySet());
        store.bundle().teams.forEach(t -> knownTeams.add(t.teamId));
        long noInvoice = ds.payments.stream().filter(p -> !ds.invoices.containsKey(p.invoiceId)).count();
        long unknownTeam = ds.payments.stream().filter(p -> !knownTeams.contains(p.teamId)).count();
        long noTime = ds.payments.stream().filter(p -> p.collectedAt == null).count();
        if (noInvoice > 0) s.warnings.add(noInvoice + " payments reference an invoice that was not uploaded; scored from payment data only.");
        if (unknownTeam > 0) s.warnings.add(unknownTeam + " payments belong to teams not seen in training; team profile features are blank.");
        if (noTime > 0) s.warnings.add(noTime + " payments have no collected_at; card history features are blank.");

        s.data = ds;
        s.history = store.bundle().historyIndex.extend(ds);
        s.payments = scoring.scorePayments(ds, s.history);
        Map<String, PaymentPrediction> byId = new HashMap<>();
        for (PaymentPrediction p : s.payments) byId.put(p.paymentId, p);
        s.paymentById = byId;
        put(s);
        return s;
    }

    /** Step 2: disputes on the step 1 payments, without outcomes. Scores every dispute. */
    public UploadSession addDisputes(String sessionId, MultipartFile disputes) throws IOException {
        UploadSession s = get(sessionId);
        if (isEmpty(disputes)) throw new SchemaException("disputes.csv is required in step 2");
        Dataset tmp = new Dataset();
        List<String> problems = new ArrayList<>();
        load(FileKind.DISPUTES, disputes, tmp, problems);
        if (!problems.isEmpty()) throw new SchemaException(problems);

        synchronized (s) {
            s.data.disputes.clear();
            s.data.disputesByPayment.clear();
            for (Dispute d : tmp.disputes) {
                d.outcome = null;   // never use outcomes even if present
                d.closedAt = null;
                s.data.addDispute(d);
            }
            long orphan = tmp.disputes.stream().filter(d -> !s.paymentById.containsKey(d.paymentId)).count();
            s.warnings.removeIf(w -> w.contains("disputes reference"));
            if (orphan > 0) s.warnings.add(orphan + " disputes reference payments not uploaded in step 1; scored without payment details.");
            s.fileRows.put("disputes", tmp.disputes.size());
            s.history = store.bundle().historyIndex.extend(s.data);
            s.disputes = scoring.scoreDisputes(s.data, s.history);
            for (PaymentPrediction p : s.payments) p.disputed = s.data.disputesByPayment.containsKey(p.paymentId);
        }
        return s;
    }

    public UploadSession get(String id) {
        UploadSession s = id == null ? null : sessions.get(id);
        if (s == null) throw new SessionNotFoundException(id);
        s.lastAccess = Instant.now();
        return s;
    }

    public Optional<UploadSession> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        UploadSession s = sessions.get(id);
        if (s != null) s.lastAccess = Instant.now();
        return Optional.ofNullable(s);
    }

    private void put(UploadSession s) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(props.getSession().getTtlHours()));
        sessions.values().removeIf(x -> x.lastAccess.isBefore(cutoff));
        while (sessions.size() >= props.getSession().getMaxSessions()) {
            sessions.values().stream().min(Comparator.comparing(x -> x.lastAccess))
                    .ifPresent(oldest -> sessions.remove(oldest.id));
        }
        sessions.put(s.id, s);
    }

    private static void load(FileKind kind, MultipartFile f, Dataset ds, List<String> problems) throws IOException {
        try (InputStream in = f.getInputStream()) {
            CsvLoader.load(kind, in, ds);
        } catch (SchemaException e) {
            problems.addAll(e.getProblems());
        } catch (IllegalArgumentException | IllegalStateException e) {
            problems.add(kind.fileName + ": " + e.getMessage());
        }
    }

    private static boolean isEmpty(MultipartFile f) {
        return f == null || f.isEmpty();
    }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String id) {
            super("Upload session " + id + " not found or expired. Upload the files again.");
        }
    }
}
