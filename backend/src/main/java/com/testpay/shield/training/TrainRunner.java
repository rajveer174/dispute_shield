package com.testpay.shield.training;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.testpay.shield.config.ShieldProperties;
import com.testpay.shield.model.BundleIO;
import com.testpay.shield.model.ModelBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code java -jar dispute-shield.jar train --data=../data --out=../models/model_bundle.json.gz}
 * Writes the bundle plus a readable training_report.json (validation table, data findings, what-if).
 */
@Component
@Profile("train")
public class TrainRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrainRunner.class);

    private final Trainer trainer;
    private final ShieldProperties props;

    public TrainRunner(Trainer trainer, ShieldProperties props) {
        this.trainer = trainer;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path data = Path.of(args.containsOption("data") ? args.getOptionValues("data").get(0) : props.getTraining().getDataDir());
        Path out = Path.of(args.containsOption("out") ? args.getOptionValues("out").get(0) : props.getModelPath());
        ModelBundle bundle = trainer.train(data);
        BundleIO.write(bundle, out);
        log.info("Wrote model bundle to {} ({} KB)", out.toAbsolutePath(), Files.size(out) / 1024);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("trainedAt", bundle.trainedAt);
        report.put("dataStart", bundle.dataStart);
        report.put("dataEnd", bundle.dataEnd);
        report.put("assumptions", bundle.assumptions);
        report.put("dataFindings", bundle.dataFindings);
        report.put("validation", bundle.validation);
        report.put("chosenApproach", bundle.chosenApproach);
        report.put("notes", bundle.notes);
        report.put("tierThresholds", Map.of("medium", bundle.tierMedium, "high", bundle.tierHigh));
        report.put("whatIf", bundle.whatIf);
        Path reportPath = (out.getParent() == null ? Path.of(".") : out.getParent()).resolve("training_report.json");
        BundleIO.MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(reportPath.toFile(), report);
        log.info("Wrote training report to {}", reportPath.toAbsolutePath());
    }
}
