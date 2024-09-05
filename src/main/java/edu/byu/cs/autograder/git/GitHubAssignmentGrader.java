package edu.byu.cs.autograder.git;

import edu.byu.cs.dataAccess.DaoService;
import edu.byu.cs.dataAccess.DataAccessException;
import edu.byu.cs.model.Phase;
import edu.byu.cs.model.Rubric;
import edu.byu.cs.model.RubricConfig;

/**
 * Grades the GitHub assignment. If a student passes the commit verification,
 * then this grader will give them full points.
 */
public class GitHubAssignmentGrader {

    public Rubric.Results grade() throws DataAccessException {
        RubricConfig rubricConfig = DaoService.getRubricConfigDao().getRubricConfig(Phase.GitHub);
        RubricConfig.RubricConfigItem configItem = rubricConfig.items().get(Rubric.RubricType.GITHUB_REPO);
        return new Rubric.Results("Successfully fetched your repository", 1f, configItem.points(), null, null);
    }

}
