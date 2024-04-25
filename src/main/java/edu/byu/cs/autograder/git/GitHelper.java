package edu.byu.cs.autograder.git;

import edu.byu.cs.analytics.CommitAnalytics;
import edu.byu.cs.analytics.CommitThreshold;
import edu.byu.cs.analytics.CommitsByDay;
import edu.byu.cs.autograder.GradingContext;
import edu.byu.cs.autograder.GradingException;
import edu.byu.cs.autograder.score.ScorerHelper;
import edu.byu.cs.dataAccess.DaoService;
import edu.byu.cs.dataAccess.SubmissionDao;
import edu.byu.cs.dataAccess.DataAccessException;
import edu.byu.cs.model.Submission;
import edu.byu.cs.util.PhaseUtils;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;

public class GitHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHelper.class);
    private final GradingContext gradingContext;

    public GitHelper(GradingContext gradingContext) {
        this.gradingContext = gradingContext;
    }

    // ## Entry Point ##
    public CommitVerificationResult setUp() throws GradingException {
        File stageRepo = gradingContext.stageRepo();
        fetchRepo(stageRepo);

        boolean gradedPhase = PhaseUtils.isPhaseGraded(gradingContext.phase());
        return gradedPhase ? verifyCommitRequirements(stageRepo) : skipCommitVerification(stageRepo);
    }

    /**
     * Fetches the student repo and puts it in the given local path
     */
    private void fetchRepo(File intoDirectory) throws GradingException {
        gradingContext.observer().update("Fetching repo...");

        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(gradingContext.repoUrl())
                .setDirectory(intoDirectory);

        try (Git git = cloneCommand.call()) {
            LOGGER.info("Cloned repo to {}", git.getRepository().getDirectory());
        } catch (GitAPIException e) {
            gradingContext.observer().notifyError("Failed to clone repo: " + e.getMessage());
            LOGGER.error("Failed to clone repo", e);
            throw new GradingException("Failed to clone repo: ",  e.getMessage());
        }

        gradingContext.observer().update("Successfully fetched repo");
    }

    // Early decisions
    private CommitVerificationResult skipCommitVerification(File stageRepo) throws GradingException {
        LOGGER.debug("Skipping commit verification");
        String headHash = getHeadHash(stageRepo);
        return new CommitVerificationResult(
                true, false,
                0, 0, null,
                Instant.MIN, Instant.MAX,
                headHash, null
        );
    }

    // Decision Logic
    private CommitVerificationResult verifyCommitRequirements(File stageRepo) throws GradingException {
        try {
            var potentialResult = preserveOriginalVerification();
            if (potentialResult != null) {
                return potentialResult;
            }

            // This could be the first passing submission. We have to calculate it from scratch.
            Collection<Submission> passingSubmissions = getPassingSubmissions();

            try (Git git = Git.open(stageRepo)) {
                return verifyRegularCommits(git, passingSubmissions);
            }
        } catch (IOException | GitAPIException | DataAccessException e) {
            var observer = gradingContext.observer();
            observer.notifyError("Failed to verify commits: " + e.getMessage());
            LOGGER.error("Failed to verify commits", e);
            throw new GradingException("Failed to verify commits: " + e.getMessage());
        }
    }

    /**
     * Performs the explicit remembering of the authorization of previous phases.
     *
     * @return Null if no decision is made. If a previous submission exists for the given phase,
     * returns a special `CommitVerificationResult` that represents the state.
     */
    private CommitVerificationResult preserveOriginalVerification() throws DataAccessException {
        Submission firstPassingSubmission = getFirstPassingSubmission();
        if (firstPassingSubmission == null || firstPassingSubmission.verifiedStatus() == null) {
            return null;
        }

        // We have a previous result to defer to:
        boolean verified = firstPassingSubmission.verifiedStatus() != Submission.VerifiedStatus.Unapproved;
        String message = verified ?
                "You passed the commit verification on your first passing submission! You're good to go!" :
                "You have previously failed commit verification.\n"+
                    "You still need to meet with a TA or a professor to gain credit for this phase.";
        return new CommitVerificationResult(
                verified, true,
                0, 0, message,
                null, null,
                firstPassingSubmission.headHash(), null
        );
    }

    /**
     * Counts the commits since the last passoff and halts progress if there are less than the required amount
     *
     * @return the number of commits since the last passoff
     */
    private CommitVerificationResult verifyRegularCommits(Git git, Collection<Submission> passingSubmissions)
            throws GitAPIException, IOException, GradingException, DataAccessException {
        CommitThreshold lowerThreshold = getMostRecentPassingSubmission(git, passingSubmissions);
        CommitThreshold upperThreshold = constructCurrentThreshold(git);

        CommitsByDay commitHistory = CommitAnalytics.countCommitsByDay(git, lowerThreshold, upperThreshold);
        CommitVerificationResult commitVerificationResult = commitsPassRequirements(commitHistory);
        LOGGER.debug("Commit verification result: " + JSON.toString(commitVerificationResult));

        var observer = gradingContext.observer();
        if (commitVerificationResult.verified()) {
            observer.update("Passed commit verification.");
        } else {
            observer.update("Failed commit verification. Continuing with grading anyways.");
        }
        return commitVerificationResult;
    }
    private CommitVerificationResult commitsPassRequirements(CommitsByDay commitsByDay) {
        int requiredCommits = gradingContext.requiredCommits();
        int requiredDaysWithCommits = gradingContext.requiredDaysWithCommits();
        int minimumLinesChangedPerCommit = gradingContext.minimumChangedLinesPerCommit();
        int commitVerificationPenaltyPct = gradingContext.commitVerificationPenaltyPct();

        int numCommits = commitsByDay.totalCommits();
        int daysWithCommits = commitsByDay.dayMap().size();
        long significantCommits = commitsByDay.changesPerCommit().stream().filter(i -> i >= minimumLinesChangedPerCommit).count();

        CV[] assertedConditions = {
                new CV(
                        numCommits < requiredCommits,
                        String.format("Not enough commits to pass off (%d/%d).", numCommits, requiredCommits)),
                new CV(
                        numCommits >= requiredCommits && significantCommits < requiredCommits,
                        String.format("Have some commits, but some of them are too insignificant for credit (%d/%d).", significantCommits, requiredCommits)),
                new CV(
                        daysWithCommits < requiredDaysWithCommits,
                        String.format("Did not commit on enough days to pass off (%d/%d).", daysWithCommits, requiredDaysWithCommits)),
                new CV(
                        commitsByDay.commitsInFuture(),
                        "Suspicious commit history. Some commits are authored after the hand in date."),
                new CV(
                        commitsByDay.commitsInPast(),
                        "Suspicious commit history. Some commits are authored before the previous phase hash."),
                new CV(
                        !commitsByDay.commitsInOrder(),
                        "Suspicious commit history. Not all commits are in order.")
        };
        ArrayList<String> errorMessages = evaluateConditions(assertedConditions, commitVerificationPenaltyPct);

        return new CommitVerificationResult(
                errorMessages.isEmpty(),
                false,
                numCommits,
                daysWithCommits,
                String.join("\n", errorMessages),
                commitsByDay.lowerThreshold().timestamp(),
                commitsByDay.upperThreshold().timestamp(),
                commitsByDay.upperThreshold().commitHash(),
                commitsByDay.lowerThreshold().commitHash()
        );
    }

    // Evaluation Helpers

    /**
     * Returns a timestamp corresponding to the most recent commit that was giving a passing grade,
     * and the commit at that point.
     * <br>
     * In any case, if the commit cannot be located, then submission timestamp will be used in its place.
     * If there are no previous passing submissions, this returns the minimum Instant instead.
     * <br>
     * Submissions on non-graded phases do not count towards the `CommitThreshold`.
     *
     * @return An {@link CommitThreshold}. Returns an empty object rather than null when no result exists.
     * @throws GradingException When certain preconditions are not met, or when this would have returned null.
     */
    @NonNull
    private CommitThreshold getMostRecentPassingSubmission(Git git, Collection<Submission> passingSubmissions)
            throws IOException, GradingException {
        if (passingSubmissions == null) {
            throw new GradingException("Cannot extract previous submission date before passingSubmissions are loaded.");
        }
        if (passingSubmissions.isEmpty()) {
            return new CommitThreshold(Instant.MIN, null);
        }

        Instant latestTimestamp = null;
        String latestCommitHash = null;
        Repository repo = git.getRepository();

        Instant effectiveSubmissionTimestamp;
        try (RevWalk revWalk = new RevWalk(repo)) {
            for (Submission submission : passingSubmissions) {
                if (!PhaseUtils.isPhaseGraded(submission.phase())) continue;

                effectiveSubmissionTimestamp = getEffectiveTimestampOfSubmission(revWalk, submission);
                revWalk.reset(); // Resetting a `revWalk` is more effective than creating a new one
                if (latestTimestamp == null || effectiveSubmissionTimestamp.isAfter(latestTimestamp)) {
                    latestTimestamp = effectiveSubmissionTimestamp;
                    latestCommitHash = submission.headHash();
                }
            }
        }

        if (latestTimestamp == null) {
            throw new GradingException("After processing a non-empty set of passing submissions, our latestTimestamp timestamp is null.");
        }

        return new CommitThreshold(latestTimestamp, latestCommitHash);
    }
    private Instant getEffectiveTimestampOfSubmission(RevWalk revWalk, Submission submission) throws IOException {
        try {
            ObjectId commitId = ObjectId.fromString(submission.headHash());
            RevCommit commit = revWalk.parseCommit(commitId);
            return Instant.ofEpochSecond(commit.getCommitTime());
        } catch (MissingObjectException | IncorrectObjectTypeException ex) {
            // The commit didn't exist. It may have been garbage collected if they rebased.
            // The hash may not have been valid. This shouldn't happen, but if it does, we'll continue.
            return submission.timestamp();
        }
    }

    /**
     * Constructs the current threshold for the grading.
     * This will represent the upper bound on the grading process.
     * <br>
     * Specifically guarantees that not only is the result NonNull,
     * but also that the `timestamp` and `headHash` fields are NonNull as well.
     *
     * @param git The `git` object of the repo to grade
     * @return A NonNull, non-null field {@link CommitThreshold}
     * @throws IOException When a file reading error occurs
     * @throws GradingException When one field would be null, or when another error occurs.
     */
    @NonNull
    private CommitThreshold constructCurrentThreshold(Git git) throws IOException, GradingException, DataAccessException {
        CommitThreshold currentThreshold = new CommitThreshold(
                ScorerHelper.getHandInDateInstant(gradingContext.netId()),
                getHeadHash(git)
        );
        if (currentThreshold.timestamp() == null) {
            throw new GradingException("Current threshold cannot have a null timestamp");
        }
        if (currentThreshold.commitHash() == null) {
            throw new GradingException("Current threshold cannot have a null commit hash");
        }

        return currentThreshold;
    }

    private static ArrayList<String> evaluateConditions(CV[] assertedConditions, int commitVerificationPenaltyPct) {
        ArrayList<String> errorMessages = new ArrayList<>();
        for (CV assertedCondition : assertedConditions) {
            if (!assertedCondition.fails) continue;
            errorMessages.add(assertedCondition.errorMsg());
        }

        if (!errorMessages.isEmpty()) {
            errorMessages.add("Since you did not meet the prerequisites for commit frequency, "
                    + "you will need to talk to a TA to receive a score. ");
            errorMessages.add(String.format("It will come with a %d%% penalty.", commitVerificationPenaltyPct));
        }
        return errorMessages;
    }

    // Helpers

    private Collection<Submission> getPassingSubmissions() throws DataAccessException {
        return DaoService.getSubmissionDao().getAllPassingSubmissions(gradingContext.netId());
    }
    private Submission getFirstPassingSubmission() throws DataAccessException {
        // CONSIDER: Rather than resolving this as a second database call,
        // read out the data from our `passingSubmissions` data that
        // we've already loaded locally.
        // This would require performing `getPassingSubmissions()` before this method.
        SubmissionDao submissionDao = DaoService.getSubmissionDao();
        return submissionDao.getFirstPassingSubmission(gradingContext.netId(), gradingContext.phase());
    }
    private String getHeadHash(File stageRepo) throws GradingException {
        try (Git git = Git.open(stageRepo)) {
            return getHeadHash(git);
        } catch (IOException e) {
            throw new GradingException("Failed to get head hash: " + e.getMessage());
        }
    }
    private String getHeadHash(Git git) throws IOException {
        return git.getRepository().findRef("HEAD").getObjectId().getName();
    }

    // Data Structures

    /** CommitValidation. Internal helper */
    private record CV(
            boolean fails,
            String errorMsg
    ) { }

}
