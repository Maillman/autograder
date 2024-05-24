package edu.byu.cs.autograder;

import edu.byu.cs.autograder.compile.CompileHelper;
import edu.byu.cs.autograder.database.DatabaseHelper;
import edu.byu.cs.autograder.git.CommitVerificationResult;
import edu.byu.cs.autograder.git.GitHelper;
import edu.byu.cs.autograder.quality.QualityGrader;
import edu.byu.cs.autograder.score.Scorer;
import edu.byu.cs.autograder.test.PassoffTestGrader;
import edu.byu.cs.autograder.test.PreviousPhasePassoffTestGrader;
import edu.byu.cs.autograder.test.UnitTestGrader;
import edu.byu.cs.dataAccess.DataAccessException;
import edu.byu.cs.model.*;
import edu.byu.cs.dataAccess.DaoService;
import edu.byu.cs.properties.ApplicationProperties;
import edu.byu.cs.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A template for fetching, compiling, and running student code
 */
public class Grader implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Grader.class);

    /** DEV ONLY. Default: true. Skips compilation and evaluation of student projects. */
    private final boolean RUN_COMPILATION = ApplicationProperties.runCompilation();

    private final DatabaseHelper dbHelper;

    private final GitHelper gitHelper;
    private final CompileHelper compileHelper;

    protected final GradingContext gradingContext;

    protected GradingObserver observer;

    /**
     * Creates a new grader
     *
     * @param repoUrl  the url of the student repo
     * @param netId    the netId of the student
     * @param observer the observer to notify of updates
     * @param phase    the phase to grade
     */
    public Grader(String repoUrl, String netId, GradingObserver observer, Phase phase, boolean admin) throws IOException, GradingException {
        // Init files
        if (!admin) {
            repoUrl = cleanRepoUrl(repoUrl);
        }
        String phasesPath = new File("./phases").getCanonicalPath();
        long salt = Instant.now().getEpochSecond();
        String stagePath = new File("./tmp-" + repoUrl.hashCode() + "-" + salt).getCanonicalPath();
        File stageRepo = new File(stagePath, "repo");

        // Init Grading Context
        int requiredCommits = 10;
        int requiredDaysWithCommits = 3;
        int commitVerificationPenaltyPct = 10;
        int minimumChangedLinesPerCommit = 5;

        this.observer = observer;
        this.gradingContext = new GradingContext(
                    netId, phase, phasesPath, stagePath, repoUrl, stageRepo,
                    requiredCommits, requiredDaysWithCommits, commitVerificationPenaltyPct, minimumChangedLinesPerCommit,
                    observer, admin);

        // Init helpers
        this.dbHelper = new DatabaseHelper(salt, gradingContext);
        this.gitHelper = new GitHelper(gradingContext);
        this.compileHelper = new CompileHelper(gradingContext);
    }

    public void run() {
        observer.notifyStarted();
        try {
            // FIXME: remove this sleep. currently the grader is too quick for the client to keep up
            Thread.sleep(1000);
            CommitVerificationResult commitVerificationResult = gitHelper.setUpAndVerifyHistory();
            dbHelper.setUp();
            if (RUN_COMPILATION) compileHelper.compile();

            if (RUN_COMPILATION) new PreviousPhasePassoffTestGrader(gradingContext).runTests();

            RubricConfig rubricConfig = DaoService.getRubricConfigDao().getRubricConfig(gradingContext.phase());
            var evaluationResults = evaluateProject(RUN_COMPILATION ? rubricConfig : null);
            Rubric rubric = assembleResultsToRubric(rubricConfig, evaluationResults);

            Submission submission = new Scorer(gradingContext).score(rubric, commitVerificationResult);
            DaoService.getSubmissionDao().insertSubmission(submission);

            observer.notifyDone(submission);
        } catch (GradingException ge) {
            if(ge.getDetails() != null) observer.notifyError(ge.getMessage(), ge.getDetails());
            else if (ge.getAnalysis() != null) observer.notifyError(ge.getMessage(), ge.getAnalysis());
            else observer.notifyError(ge.getMessage());
            String notification =
                    "Error running grader for user " + gradingContext.netId() + " and repository " + gradingContext.repoUrl();
            if(ge.getDetails() != null) notification += ". Details:\n" + ge.getDetails();
            LOGGER.error(notification, ge);
        } catch (Exception e) {
            observer.notifyError(e.getMessage());
            LOGGER.error("Error running grader for user {} and repository {}", gradingContext.netId(),
                    gradingContext.repoUrl(), e);
        } finally {
            dbHelper.cleanUp();
            FileUtils.removeDirectory(new File(gradingContext.stagePath()));
        }
    }

    private Map<String, Rubric.Results> evaluateProject(RubricConfig rubricConfig) throws GradingException, DataAccessException {
        // NOTE: Ideally these would be treated with enum types. That will need to be improved with #300.
        Map<String, Rubric.Results> out = new HashMap<>();
        if (rubricConfig == null) {
            return out;
        }

        if(rubricConfig.quality() != null) {
            out.put("quality", new QualityGrader(gradingContext).runQualityChecks());
        }
        if(rubricConfig.passoffTests() != null) {
            out.put("passoff", new PassoffTestGrader(gradingContext).runTests());
        }
        if(rubricConfig.unitTests() != null) {
            out.put("customTests", new UnitTestGrader(gradingContext).runTests());
        }

        return out;
    }

    private Rubric assembleResultsToRubric(RubricConfig rubricConfig, Map<String, Rubric.Results> evaluatedResults) {
        Rubric.Results qualityResults = evaluatedResults.get("quality");
        Rubric.Results passoffResults = evaluatedResults.get("passoff");
        Rubric.Results customTestsResults = evaluatedResults.get("customTests");

        Rubric.RubricItem qualityItem = null;
        Rubric.RubricItem passoffItem = null;
        Rubric.RubricItem customTestsItem = null;

        if (qualityResults != null)
            qualityItem = new Rubric.RubricItem(rubricConfig.quality().category(), qualityResults, rubricConfig.quality().criteria());
        if (passoffResults != null)
            passoffItem = new Rubric.RubricItem(rubricConfig.passoffTests().category(), passoffResults, rubricConfig.passoffTests().criteria());
        if (customTestsResults != null)
            customTestsItem = new Rubric.RubricItem(rubricConfig.unitTests().category(), customTestsResults, rubricConfig.unitTests().criteria());

        return new Rubric(passoffItem, customTestsItem, qualityItem, null, false, "");
    }

    /**
     * Cleans the student's by removing trailing characters after the repo name,
     * unless it ends in `.git`.
     *
     * @param repoUrl The student's repository URL.
     * @return Cleaned URL with everything after the repo name stripped off.
     * @throws GradingException Throws IOException if repoUrl does not follow expected format
     */
    public static String cleanRepoUrl(String repoUrl) throws GradingException {
        String[] regexPatterns = {
            "https?://github\\.com/([^/?]+)/([^/?]+)", // https
            "git@github.com:([^/]+)/([^/]+).git" // ssh
        };
        Pattern pattern;
        Matcher matcher;
        String githubUsername;
        String repositoryName;
        for (String regexPattern: regexPatterns) {
            pattern = Pattern.compile(regexPattern);
            matcher = pattern.matcher(repoUrl);
            if (matcher.find()) {
                githubUsername = matcher.group(1);
                repositoryName = matcher.group(2);
                return String.format("https://github.com/%s/%s", githubUsername, repositoryName);
            }
        }
        throw new GradingException("Could not find github username and repository name given '" + repoUrl + "'.");
    }

}
