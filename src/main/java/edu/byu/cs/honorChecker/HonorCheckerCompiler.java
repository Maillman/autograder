package edu.byu.cs.honorChecker;

import edu.byu.cs.canvas.CanvasException;
import edu.byu.cs.canvas.CanvasIntegration;
import edu.byu.cs.model.User;
import edu.byu.cs.util.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;

import java.io.File;
import java.util.Collection;
import java.util.function.Consumer;

public class HonorCheckerCompiler {

    /**
     * Creates a .zip file for all students' repos in the given section
     *
     * @param section the section number (not ID)
     * @return the path to the .zip file
     */
    public static String compileSection(int section) {
        int sectionID = CanvasIntegration.sectionIDs.get(section);
        String tmpDir = "tmp-section-" + section;
        String zipFilePath = "section-" + section + ".zip";

        FileUtils.createDirectory(tmpDir);

        Collection<User> students;
        try {
            students = CanvasIntegration.getAllStudentsBySection(sectionID);
        } catch (CanvasException e) {
            throw new RuntimeException("Canvas Exception: " + e.getMessage());
        }

        try {
            for (User student : students) {
                File repoPath = new File(tmpDir, student.netId());

                CloneCommand cloneCommand = Git.cloneRepository()
                        .setURI(student.repoUrl())
                        .setDirectory(repoPath);

                try {
                    Git git = cloneCommand.call();
                    git.close();
                } catch (Exception e) {
                    FileUtils.removeDirectory(repoPath);
                    continue;
                }

                // delete everything except modules
                Consumer<File> action = file -> {
                    String prefix = repoPath + File.separator;
                    if (!file.getPath().startsWith(prefix + "client") &&
                        !file.getPath().startsWith(prefix + "server") &&
                        !file.getPath().startsWith(prefix + "shared")) {
                        file.delete();
                    }
                };
                FileUtils.modifyDirectory(new File(repoPath.getPath()), action);
            }

            FileUtils.zipDirectory(tmpDir, zipFilePath);
        } catch (RuntimeException e) {
            FileUtils.removeDirectory(new File(tmpDir));
            new File(zipFilePath).delete();
            throw e;
        }

        FileUtils.removeDirectory(new File(tmpDir));
        return zipFilePath;
    }
}