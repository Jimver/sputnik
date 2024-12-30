package pl.touk.sputnik.processor.pmd;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pmd.PMDConfiguration;

import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RulePriority;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import pl.touk.sputnik.configuration.Configuration;
import pl.touk.sputnik.configuration.GeneralOption;
import pl.touk.sputnik.review.Review;
import pl.touk.sputnik.review.ReviewException;
import pl.touk.sputnik.review.ReviewProcessor;
import pl.touk.sputnik.review.ReviewResult;
import pl.touk.sputnik.review.Severity;
import pl.touk.sputnik.review.Violation;
import pl.touk.sputnik.review.filter.PmdFilter;
import pl.touk.sputnik.review.transformer.FileNameTransformer;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class PmdProcessor implements ReviewProcessor {
    private static final char LINE_SEPARATOR = '\n';
    private static final String SOURCE_NAME = "PMD";
    private static final String PMD_INPUT_PATH_SEPARATOR = ",";

    @NotNull
    private final Configuration config;

    public PmdProcessor(Configuration configuration) {
        config = configuration;
    }

    @Nullable
    @Override
    public ReviewResult process(@NotNull Review review) {
        List<Path> filesToReview = review.getFiles(new PmdFilter(), new FileNameTransformer()).stream()
            .map(file -> {
                Path path = FileSystems.getDefault().getPath(file);
                if (!path.toFile().exists()) {
                    throw new ReviewException("File [" + file + "] does not exist");
                }
                return path;
            })
            .collect(Collectors.toList());
        if (filesToReview.isEmpty()) {
            return null;
        }

        try {
            PMDConfiguration configuration = new PMDConfiguration();
            configuration.setRuleSets(getRulesets());
            configuration.setInputPathList(filesToReview);
            Report report = doPMD(configuration);
            return convertReportToReview(report);
        } catch (RuntimeException e) {
            log.error("PMD processing error. Something wrong with configuration or analyzed files are not in workspace.", e);
            throw new ReviewException("PMD processing error", e);
        }
    }

    @NotNull
    private ReviewResult convertReportToReview(Report report) {
        ReviewResult reviewResult = new ReviewResult();
        boolean showDetails = Boolean.parseBoolean(config.getProperty(GeneralOption.PMD_SHOW_VIOLATION_DETAILS));
        for (RuleViolation ruleViolation : report.getViolations()) {
            String violationDescription = showDetails ? renderViolationDetails(ruleViolation) :ruleViolation.getDescription();
            FileId myFileId = ruleViolation.getFileId();
            reviewResult.add(new Violation(myFileId.getOriginalPath(), ruleViolation.getBeginLine(), violationDescription, convert(ruleViolation.getRule().getPriority())));
        }
        return reviewResult;
    }

    @NotNull
    @Override
    public String getName() {
        return SOURCE_NAME;
    }

    private List<String> getRulesets() {
        String ruleSets = config.getProperty(GeneralOption.PMD_RULESETS);
        log.info("Using PMD rulesets {}", ruleSets);
        if (ruleSets == null) {
            return new ArrayList<>();
        }
        return Arrays.asList(ruleSets.split(PMD_INPUT_PATH_SEPARATOR));
    }

    /**
     * Run PMD analysis
     *
     * @return Report from PMD
     * @throws IllegalArgumentException if the configuration is not correct
     */
    @NotNull
    private Report doPMD(@NotNull PMDConfiguration configuration) throws IllegalArgumentException {
        try (PmdAnalysis analysis = PmdAnalysis.create(configuration)) {
            return analysis.performAnalysisAndCollectReport();
        }
    }

    @NotNull
    private static Severity convert(@NotNull RulePriority rulePriority) {
        switch (rulePriority) {
            case HIGH:
                return Severity.ERROR;
            case MEDIUM_HIGH:
                return Severity.WARNING;
            case MEDIUM:
            case MEDIUM_LOW:
                return Severity.INFO;
            case LOW:
                return Severity.IGNORE;
            default:
                throw new IllegalArgumentException("RulePriority " + rulePriority + " is not supported");
        }
    }

    private static String renderViolationDetails(RuleViolation ruleViolation) {
        StringBuilder fullDescription = new StringBuilder(ruleViolation.getDescription());

        String reason = ruleViolation.getRule().getDescription();
        if (StringUtils.isNotEmpty(reason)) {
            fullDescription.append(LINE_SEPARATOR).append(reason);
        }
        String url = ruleViolation.getRule().getExternalInfoUrl();
        if (StringUtils.isNotEmpty(url)) {
            fullDescription.append(LINE_SEPARATOR).append(url);
        }

        return fullDescription.toString();
    }
}
