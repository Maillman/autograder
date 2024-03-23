package edu.byu.cs.autograder;

import edu.byu.cs.analytics.CommitAnalytics;
import edu.byu.cs.canvas.CanvasException;
import edu.byu.cs.canvas.CanvasIntegration;
import edu.byu.cs.canvas.CanvasUtils;
import edu.byu.cs.model.*;
import edu.byu.cs.dataAccess.DaoService;
import edu.byu.cs.dataAccess.SubmissionDao;
import edu.byu.cs.dataAccess.UserDao;
import edu.byu.cs.util.DateTimeUtils;
import edu.byu.cs.util.FileUtils;
import edu.byu.cs.util.PhaseUtils;
import edu.byu.cs.util.ProcessUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * A template for fetching, compiling, and running student code
 */
public abstract class Grader implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Grader.class);

    /**
     * The netId of the student
     */
    protected final String netId;

    /**
     * The phase to grade
     */
    protected final Phase phase;

    /**
     * The path where the official tests are stored
     */
    protected final String phasesPath;

    /**
     * The path where JUnit jars are stored
     */
    protected final String libsDir;

    /**
     * The path to the standalone JUnit jar
     */
    protected final String standaloneJunitJarPath;

    /**
     * The path to the JUnit Jupiter API jar
     */
    protected final String junitJupiterApiJarPath;

    /**
     * The path to the passoff dependencies jar
     */
    protected final String passoffDependenciesPath;

    /**
     * The path for the student repo to be put in and tested
     */
    protected final String stagePath;

    /**
     * The url of the student repo
     */
    private final String repoUrl;

    private final DatabaseHelper dbHelper;


    /**
     * The path for the student repo (child of stagePath)
     */

    protected final File stageRepo;

    /**
     * The required number of commits (since the last phase) to be able to pass off
     */
    private final int requiredCommits;

    /**
     * The max number of days that the late penalty should be applied for.
     */
    private static final int MAX_LATE_DAYS_TO_PENALIZE = 5;

    /**
     * The penalty to be applied per day to a late submission.
     * This is out of 1. So putting 0.1 would be a 10% deduction per day
     */
    private static final float PER_DAY_LATE_PENALTY = 0.1F;

    protected static final String PASSOFF_TESTS_NAME = "Passoff Tests";
    protected static final String CUSTOM_TESTS_NAME = "Custom Tests";

    protected Observer observer;

    private DateTimeUtils dateTimeUtils;

    /**
     * Creates a new grader
     *
     * @param repoUrl  the url of the student repo
     * @param netId    the netId of the student
     * @param observer the observer to notify of updates
     * @param phase    the phase to grade
     */
    public Grader(String repoUrl, String netId, Observer observer, Phase phase) throws IOException {
        this.netId = netId;
        this.phase = phase;
        this.phasesPath = new File("./phases").getCanonicalPath();
        this.libsDir = new File(phasesPath, "libs").getCanonicalPath();
        this.standaloneJunitJarPath = new File(libsDir, "junit-platform-console-standalone-1.10.1.jar").getCanonicalPath();
        this.junitJupiterApiJarPath = new File(libsDir, "junit-jupiter-api-5.10.1.jar").getCanonicalPath();
        this.passoffDependenciesPath = new File(libsDir, "passoff-dependencies.jar").getCanonicalPath();

        long salt = Instant.now().getEpochSecond();
        this.stagePath = new File("./tmp-" + repoUrl.hashCode() + "-" + salt).getCanonicalPath();

        this.repoUrl = repoUrl;
        this.stageRepo = new File(stagePath, "repo");

        this.dbHelper = new DatabaseHelper(salt);

        this.requiredCommits = 10;

        this.observer = observer;

        this.initializeDateUtils();
    }

    private void initializeDateUtils() {
        this.dateTimeUtils = new DateTimeUtils();
        dateTimeUtils.initializePublicHolidays(getEncodedPublicHolidays());
    }
    private String getEncodedPublicHolidays() {
        // FIXME: Return from some dynamic location like a configuration file or a configurable table
        return "1/1/2024;1/15/2024;2/19/2024;3/15/2024;4/25/2024;5/27/2024;6/19/2024;"
         + "7/4/2024;7/24/2024;9/2/2024;11/27/2024;11/28/2024;11/29/2024;12/24/2024;12/25/2024;12/31/2024;"
         + "1/1/2025;";
    }

    public void run() {
        observer.notifyStarted();
        Collection<String> existingDatabaseNames = new HashSet<>();
        boolean finishedCleaningDatabase = false;
        try {
            // FIXME: remove this sleep. currently the grader is too quick for the client to keep up
            Thread.sleep(1000);
            fetchRepo();
            int numCommits = verifyRegularCommits();
            verifyProjectStructure();

            existingDatabaseNames = dbHelper.getExistingDatabaseNames();
            dbHelper.injectDatabaseConfig(stageRepo);

            modifyPoms();

            packageRepo();

            RubricConfig rubricConfig = DaoService.getRubricConfigDao().getRubricConfig(phase);
            Rubric.Results qualityResults = null;
            if(rubricConfig.quality() != null) {
                qualityResults = runQualityChecks();
            }


            Rubric.Results passoffResults = null;
            if(rubricConfig.passoffTests() != null) {
                compileTests();
                passoffResults = runTests(getPackagesToTest());
            }
            Rubric.Results customTestsResults = null;
            if(rubricConfig.unitTests() != null) {
                customTestsResults = runCustomTests();
            }

            dbHelper.cleanupDatabase();
            dbHelper.assertNoExtraDatabases(existingDatabaseNames, dbHelper.getExistingDatabaseNames());
            finishedCleaningDatabase = true;

            Rubric.RubricItem qualityItem = null;
            Rubric.RubricItem passoffItem = null;
            Rubric.RubricItem customTestsItem = null;

            if (qualityResults != null)
                qualityItem = new Rubric.RubricItem(rubricConfig.quality().category(), qualityResults, rubricConfig.quality().criteria());
            if (passoffResults != null)
                passoffItem = new Rubric.RubricItem(rubricConfig.passoffTests().category(), passoffResults, rubricConfig.passoffTests().criteria());
            if (customTestsResults != null)
                customTestsItem = new Rubric.RubricItem(rubricConfig.unitTests().category(), customTestsResults, rubricConfig.unitTests().criteria());

            Rubric rubric = new Rubric(passoffItem, customTestsItem, qualityItem, false, "");
            rubric = CanvasUtils.decimalScoreToPoints(phase, rubric);
            rubric = annotateRubric(rubric);

            int daysLate = calculateLateDays();
            float thisScore = calculateScoreWithLatePenalty(rubric, daysLate);
            Submission thisSubmission;

            // prevent score from being saved to canvas if it will lower their score
            if(rubric.passed()) {
                float highestScore = getCanvasScore();

                // prevent score from being saved to canvas if it will lower their score
                if (thisScore <= highestScore && phase != Phase.Phase5 && phase != Phase.Phase6) {
                    String notes = "Submission did not improve current score. (" + (highestScore * 100) +
                            "%) Score not saved to Canvas.\n";
                    thisSubmission = saveResults(rubric, numCommits, daysLate, thisScore, notes);
                } else {
                    thisSubmission = saveResults(rubric, numCommits, daysLate, thisScore, "");
                    sendToCanvas(thisSubmission, 1 - (daysLate * PER_DAY_LATE_PENALTY));
                }
            }
            else {
                thisSubmission = saveResults(rubric, numCommits, daysLate, thisScore, "");
            }

            observer.notifyDone(thisSubmission);

        } catch (GradingException ge) {
            observer.notifyError(ge.getMessage(), ge.getDetails());
            String notification = "Error running grader for user " + netId + " and repository " + repoUrl;
            if(ge.getDetails() != null) notification += ". Details:\n" + ge.getDetails();
            LOGGER.error(notification, ge);
        }
        catch (Exception e) {
            observer.notifyError(e.getMessage());
            LOGGER.error("Error running grader for user " + netId + " and repository " + repoUrl, e);
        } finally {
            if(!finishedCleaningDatabase) {
                try {
                    dbHelper.cleanupDatabase();
                    Collection<String> currentDatabaseNames = dbHelper.getExistingDatabaseNames();
                    currentDatabaseNames.removeAll(existingDatabaseNames);
                    dbHelper.cleanUpExtraDatabases(currentDatabaseNames);
                } catch (GradingException e) {
                    LOGGER.error("Error cleaning up after user " + netId + " and repository " + repoUrl, e);
                }
            }
            FileUtils.removeDirectory(new File(stagePath));
        }
    }

    protected abstract Set<String> getPackagesToTest();

    private void modifyPoms() {
        File oldRootPom = new File(stageRepo, "pom.xml");
        File oldServerPom = new File(stageRepo, "server/pom.xml");
        File oldClientPom = new File(stageRepo, "client/pom.xml");
        File oldSharedPom = new File(stageRepo, "shared/pom.xml");

        File newRootPom = new File(phasesPath, "pom/pom.xml");
        File newServerPom = new File(phasesPath, "pom/server/pom.xml");
        File newClientPom = new File(phasesPath, "pom/client/pom.xml");
        File newSharedPom = new File(phasesPath, "pom/shared/pom.xml");

        FileUtils.copyFile(oldRootPom, newRootPom);
        FileUtils.copyFile(oldServerPom, newServerPom);
        FileUtils.copyFile(oldClientPom, newClientPom);
        FileUtils.copyFile(oldSharedPom, newSharedPom);
    }

    /**
     * gets the score stored in canvas for the current user and phase
     * @return score. returns 1.0 for a score of 100%. returns 0.5 for a score of 50%.
     */
    private float getCanvasScore() throws GradingException {
        User user = DaoService.getUserDao().getUser(netId);

        int userId = user.canvasUserId();

        int assignmentNum = PhaseUtils.getPhaseAssignmentNumber(phase);
        try {
            CanvasIntegration.CanvasSubmission submission = CanvasIntegration.getSubmission(userId, assignmentNum);
            int totalPossiblePoints = DaoService.getRubricConfigDao().getPhaseTotalPossiblePoints(phase);
            return submission.score() == null ? 0 : submission.score() / totalPossiblePoints;
        } catch (CanvasException e) {
            throw new GradingException(e);
        }
    }

    /**
     * Runs quality checks on the student's code
     *
     * @return the results of the quality checks as a CanvasIntegration.RubricItem
     */
    protected abstract Rubric.Results runQualityChecks() throws GradingException;

    /**
     * Verifies that the project is structured correctly. The project should be at the top level of the git repository,
     * which is checked by looking for a pom.xml file
     */
    private void verifyProjectStructure() throws GradingException {
        File pomFile = new File(stageRepo, "pom.xml");
        if (!pomFile.exists()) {
            observer.notifyError("Project is not structured correctly. Your project should be at the top level of your git repository.");
            throw new GradingException("No pom.xml file found");
        }
    }

    private int calculateLateDays() throws GradingException {
        int assignmentNum = PhaseUtils.getPhaseAssignmentNumber(phase);

        int canvasUserId = DaoService.getUserDao().getUser(netId).canvasUserId();

        ZonedDateTime dueDate;
        try {
            dueDate = CanvasIntegration.getAssignmentDueDateForStudent(canvasUserId, assignmentNum);
        } catch (CanvasException e) {
            throw new GradingException("Failed to get due date for assignment " + assignmentNum + " for user " + netId, e);
        }

        ZonedDateTime handInDate = DaoService.getQueueDao().get(netId).timeAdded().atZone(ZoneId.of("America/Denver"));
        return Math.min(dateTimeUtils.getNumDaysLate(handInDate, dueDate), MAX_LATE_DAYS_TO_PENALIZE);
    }

    private float calculateScoreWithLatePenalty(Rubric rubric, int numDaysLate) throws GradingException {
        float score = getScore(rubric);
        score -= numDaysLate * PER_DAY_LATE_PENALTY;
        if (score < 0) score = 0;
        return score;
    }

    /**
     * Saves the results of the grading to the database if the submission passed
     *
     * @param rubric the rubric for the phase
     */
    private Submission saveResults(Rubric rubric, int numCommits, int numDaysLate, float score, String notes)
            throws GradingException {
        String headHash = getHeadHash();

        if (numDaysLate > 0)
            notes += numDaysLate + " days late. -" + (numDaysLate * 10) + "%";

        // FIXME: this is code duplication from calculateLateDays()
        ZonedDateTime handInDate = DaoService.getQueueDao().get(netId).timeAdded().atZone(ZoneId.of("America/Denver"));

        SubmissionDao submissionDao = DaoService.getSubmissionDao();
        Submission submission = new Submission(
                netId,
                repoUrl,
                headHash,
                handInDate.toInstant(),
                phase,
                rubric.passed(),
                score,
                numCommits,
                notes,
                rubric
        );

        submissionDao.insertSubmission(submission);
        return submission;
    }

    private void sendToCanvas(Submission submission, float lateAdjustment) throws GradingException {
        UserDao userDao = DaoService.getUserDao();
        User user = userDao.getUser(netId);

        int userId = user.canvasUserId();

        int assignmentNum = PhaseUtils.getPhaseAssignmentNumber(phase);

        RubricConfig rubricConfig = DaoService.getRubricConfigDao().getRubricConfig(phase);
        Map<String, Float> scores = new HashMap<>();
        Map<String, String> comments = new HashMap<>();

        convertToCanvasFormat(submission.rubric().passoffTests(), lateAdjustment, rubricConfig.passoffTests(), scores, comments, Rubric.RubricType.PASSOFF_TESTS);
        convertToCanvasFormat(submission.rubric().unitTests(), lateAdjustment, rubricConfig.unitTests(), scores, comments, Rubric.RubricType.UNIT_TESTS);
        convertToCanvasFormat(submission.rubric().quality(), lateAdjustment, rubricConfig.quality(), scores, comments, Rubric.RubricType.QUALITY);

        try {
            CanvasIntegration.submitGrade(userId, assignmentNum, scores, comments, submission.notes());
        } catch (CanvasException e) {
            LOGGER.error("Error submitting to canvas for user " + submission.netId(), e);
            throw new GradingException("Error contacting canvas to record scores");
        }

    }

    private void convertToCanvasFormat(Rubric.RubricItem rubricItem, float lateAdjustment,
                                       RubricConfig.RubricConfigItem rubricConfigItem, Map<String, Float> scores,
                                       Map<String, String> comments, Rubric.RubricType rubricType)
            throws GradingException {
        if (rubricConfigItem != null && rubricConfigItem.points() > 0) {
            String id = getCanvasRubricId(rubricType);
            Rubric.Results results = rubricItem.results();
            scores.put(id, results.score() * lateAdjustment);
            comments.put(id, results.notes());
        }
    }

    private String getHeadHash() throws GradingException {
        String headHash;
        try (Git git = Git.open(stageRepo)) {
            headHash = git.getRepository().findRef("HEAD").getObjectId().getName();
        } catch (IOException e) {
            throw new GradingException("Failed to get head hash: " + e.getMessage());
        }
        return headHash;
    }

    /**
     * Fetches the student repo and puts it in the given local path
     */
    private void fetchRepo() throws GradingException {
        observer.update("Fetching repo...");

        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(stageRepo);

        try (Git git = cloneCommand.call()) {
            LOGGER.info("Cloned repo to " + git.getRepository().getDirectory());
        } catch (GitAPIException e) {
            observer.notifyError("Failed to clone repo: " + e.getMessage());
            LOGGER.error("Failed to clone repo", e);
            throw new GradingException("Failed to clone repo: ",  e.getMessage());
        }

        observer.update("Successfully fetched repo");
    }

    /**
     * Counts the commits since the last passoff and halts progress if there are less than the required amount
     *
     * @return the number of commits since the last passoff
     */
    private int verifyRegularCommits() throws GradingException {
        observer.update("Verifying commits...");

        try (Git git = Git.open(stageRepo)) {
            Iterable<RevCommit> commits = git.log().all().call();
            Submission submission = DaoService.getSubmissionDao().getFirstPassingSubmission(netId, phase);
            long timestamp = submission == null ? 0L : submission.timestamp().getEpochSecond();
            Map<String, Integer> commitHistory = CommitAnalytics.handleCommits(commits, timestamp, Instant.now().getEpochSecond());
            int numCommits = CommitAnalytics.getTotalCommits(commitHistory);
//            if (numCommits < requiredCommits) {
//                observer.notifyError("Not enough commits to pass off. (" + numCommits + "/" + requiredCommits + ")");
//                LOGGER.error("Insufficient commits to pass off.");
//                throw new GradingException("Not enough commits to pass off");
//            }

            return numCommits;
        } catch (IOException | GitAPIException e) {
            observer.notifyError("Failed to count commits: " + e.getMessage());
            LOGGER.error("Failed to count commits", e);
            throw new GradingException("Failed to count commits: ", e.getMessage());
        }
    }

    /**
     * Packages the student repo into a jar
     */
    protected void packageRepo() throws GradingException {
        observer.update("Packaging repo...");

        observer.update("  Running maven package command...");
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(stageRepo);
        processBuilder.command("mvn", "package", "-DskipTests");
        try {
            ProcessUtils.ProcessOutput output = ProcessUtils.runProcess(processBuilder, 90000); //90 seconds
            if (output.statusCode() != 0) {
                throw new GradingException("Failed to package repo: ", getMavenError(output.stdOut()));
            }
        } catch (ProcessUtils.ProcessException ex) {
            throw new GradingException("Failed to package repo", ex);
        }

        observer.update("Successfully packaged repo");
    }

    /**
     * Retrieves maven error output from maven package stdout
     *
     * @param output A string containing maven standard output
     * @return A string containing maven package error lines
     */
    private String getMavenError(String output) {
        StringBuilder builder = new StringBuilder();
        for (String line : output.split("\n")) {
            if (line.contains("[ERROR] -> [Help 1]")) {
                break;
            }

            if(line.contains("[ERROR]")) {
                String trimLine = line.replace(stageRepo.getAbsolutePath(), "");
                builder.append(trimLine).append("\n");
            }
        }
        return builder.toString();
    }

    /**
     * Run the unit tests written by the student. This approach is destructive as it will delete non-unit tests
     *
     * @return the results of the tests
     */
    protected abstract Rubric.Results runCustomTests() throws GradingException;

    /**
     * Compiles the test files with the student code
     */
    protected abstract void compileTests() throws GradingException;

    /**
     * Runs the tests on the student code
     */
    protected abstract Rubric.Results runTests(Set<String> packagesToTest) throws GradingException;

    /**
     * Gets the score for the phase
     *
     * @return the score
     */
    protected float getScore(Rubric rubric) throws GradingException {
        int totalPossiblePoints = DaoService.getRubricConfigDao().getPhaseTotalPossiblePoints(phase);

        if (totalPossiblePoints == 0)
            throw new GradingException("Total possible points for phase " + phase + " is 0");

        float score = 0;
        if (rubric.passoffTests() != null)
            score += rubric.passoffTests().results().score();

        if (rubric.unitTests() != null)
            score += rubric.unitTests().results().score();

        if (rubric.quality() != null)
            score += rubric.quality().results().score();

        return score / totalPossiblePoints;
    }

    protected abstract boolean passed(Rubric rubric) throws GradingException;

    /**
     * Annotates the rubric with notes and passed status
     *
     * @param rubric the rubric to annotate
     * @return the annotated rubric
     */
    protected abstract Rubric annotateRubric(Rubric rubric) throws GradingException;

    protected abstract String getCanvasRubricId(Rubric.RubricType type) throws GradingException;

    public interface Observer {
        void notifyStarted();

        void update(String message);

        void notifyError(String message);
        void notifyError(String message, String details);

        void notifyDone(Submission submission);
    }

}
