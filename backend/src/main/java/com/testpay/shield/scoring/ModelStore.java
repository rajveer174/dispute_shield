package com.testpay.shield.scoring;

import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.model.BundleIO;
import com.testpay.shield.model.ModelBundle;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the trained bundle once at startup. The app never retrains. */
@Component
@Profile("!train")
public class ModelStore {

    private static final Logger log = LoggerFactory.getLogger(ModelStore.class);

    private final ShieldProperties props;
    private volatile ModelBundle bundle;
    private volatile String loadError;

    public ModelStore(ShieldProperties props) {
        this.props = props;
    }

    @PostConstruct
    void load() {
        Path p = Path.of(props.getModelPath());
        if (!Files.exists(p) && Files.exists(Path.of(p + ".gz"))) p = Path.of(p + ".gz");
        try {
            if (!Files.exists(p)) throw new IllegalStateException("No model bundle at " + p.toAbsolutePath() + ". Run the trainer first.");
            bundle = BundleIO.read(p);
            log.info("Loaded model bundle trained at {} on data {} .. {}", bundle.trainedAt, bundle.dataStart, bundle.dataEnd);
        } catch (Exception e) {
            loadError = e.getMessage();
            log.error("Model bundle not loaded: {}", e.getMessage());
        }
    }

    public boolean isReady() {
        return bundle != null;
    }

    public String loadError() {
        return loadError;
    }

    public ModelBundle bundle() {
        if (bundle == null) throw new ModelNotReadyException(loadError);
        return bundle;
    }

    /** True when the bundle has a three-rule table for this moment (bundles trained before the rules have none). */
    public boolean hasRules(String moment) {
        return bundle().winRules != null && bundle().winRules.get(moment) != null;
    }

    /** A rule table is published on the same AUC bar as the models, using its test months. */
    public boolean rulesPublishable(String moment) {
        if (!hasRules(moment)) return false;
        double auc = bundle().winRules.get(moment).testAuc;
        return Double.isNaN(auc) || auc >= props.getExport().getMinAucToPublish();
    }

    /** A model is published only if it beat the AUC bar on the time-based validation split. */
    public boolean publishable(String key) {
        double auc = bundle().validationAuc(key);
        return Double.isNaN(auc) || auc >= props.getExport().getMinAucToPublish();
    }

    public static class ModelNotReadyException extends RuntimeException {
        public ModelNotReadyException(String msg) {
            super("Models are not loaded: " + msg);
        }
    }
}
