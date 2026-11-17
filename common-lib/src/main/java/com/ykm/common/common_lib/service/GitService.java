package com.ykm.common.common_lib.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class GitService {

    private static final String REMOTE_NAME = "origin";

    public UploadSession beginUpload(String remoteUrl, String branch, Path deployKey, Path cacheDirectory) throws IOException {
        return new UploadSession(remoteUrl, branch, deployKey, cacheDirectory);
    }

    public static final class UploadSession implements AutoCloseable {
        private final Path gitDirectory;
        private final Path indexFile;
        private final String branch;
        private final String sshCommand;
        private boolean committed;

        private UploadSession(String remoteUrl, String branch, Path deployKey, Path cacheDirectory) throws IOException {
            if (remoteUrl == null || remoteUrl.isBlank()) throw new IllegalArgumentException("remoteUrl is required");
            if (branch == null || branch.isBlank()) throw new IllegalArgumentException("branch is required");
            if (deployKey == null || !Files.isRegularFile(deployKey)) throw new IllegalArgumentException("deployKey does not exist: " + deployKey);
            this.gitDirectory = cacheDirectory.toAbsolutePath().normalize();
            this.branch = branch;
            this.sshCommand = "ssh -i " + shellQuote(deployKey.toAbsolutePath().normalize().toString())
                    + " -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new";

            initializeBareRepository(remoteUrl);
            fetchBranch();
            Path parent = gitDirectory.getParent();
            if (parent != null) Files.createDirectories(parent);
            this.indexFile = Files.createTempFile(parent, "git-upload-", ".index");
            Files.deleteIfExists(indexFile);
            runGit(null, "read-tree", remoteRef());
        }

        public void replaceDirectory(String repositoryDirectory) throws IOException {
            String prefix = normalizeRepositoryPath(repositoryDirectory);
            String output = runGitCapture(null, "ls-tree", "-r", "--name-only", remoteRef(), "--", prefix);
            List<String> paths = output.lines().filter(line -> !line.isBlank()).toList();
            for (int from = 0; from < paths.size(); from += 200) {
                int to = Math.min(paths.size(), from + 200);
                List<String> args = new ArrayList<>();
                args.add("update-index");
                args.add("--force-remove");
                args.add("--");
                args.addAll(paths.subList(from, to));
                runGit(null, args.toArray(String[]::new));
            }
        }

        public boolean directoryExists(String repositoryDirectory) throws IOException {
            String prefix = normalizeRepositoryPath(repositoryDirectory);
            String output = runGitCapture(null, "ls-tree", "-r", "--name-only", remoteRef(), "--", prefix);
            return output.lines().anyMatch(line -> line.equals(prefix) || line.startsWith(prefix + "/"));
        }

        public void deleteFile(String repositoryPath) throws IOException {
            if (committed) throw new IllegalStateException("Upload session is already committed");
            String path = normalizeRepositoryPath(repositoryPath);
            byte[] indexInfo = ("0 0000000000000000000000000000000000000000\t" + path + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            runGit(indexInfo, "update-index", "--index-info");
        }

        public void putFile(String repositoryPath, byte[] content) throws IOException {
            if (committed) throw new IllegalStateException("Upload session is already committed");
            String path = normalizeRepositoryPath(repositoryPath);
            String objectId = runGitCapture(content, "hash-object", "-w", "--stdin").trim();
            runGit(null, "update-index", "--add", "--cacheinfo", "100644", objectId, path);
        }

        public String commitAndPush(String message) throws IOException {
            if (committed) throw new IllegalStateException("Upload session is already committed");
            String tree = runGitCapture(null, "write-tree").trim();
            String parent = runGitCapture(null, "rev-parse", remoteRef()).trim();
            String commit = runGitCapture(null,
                    "-c", "user.name=stock-data-analysis",
                    "-c", "user.email=stock-data-analysis@localhost",
                    "commit-tree", tree, "-p", parent, "-m", message).trim();
            runGitWithoutIndex(null, "push", REMOTE_NAME, commit + ":refs/heads/" + branch);
            runGitWithoutIndex(null, "update-ref", remoteRef(), commit);
            committed = true;
            return commit;
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(indexFile);
        }

        private void initializeBareRepository(String remoteUrl) throws IOException {
            Files.createDirectories(gitDirectory);
            if (!Files.isDirectory(gitDirectory.resolve("objects"))) {
                runCommand(null, null, "git", "init", "--bare", gitDirectory.toString());
            }
            CommandResult remote = runCommandAllowFailure(null, null,
                    "git", "--git-dir=" + gitDirectory, "remote", "get-url", REMOTE_NAME);
            if (remote.exitCode() == 0) {
                runGitWithoutIndex(null, "remote", "set-url", REMOTE_NAME, remoteUrl);
            } else {
                runGitWithoutIndex(null, "remote", "add", REMOTE_NAME, remoteUrl);
            }
        }

        private void fetchBranch() throws IOException {
            runGitWithoutIndex(null, "fetch", "--prune", REMOTE_NAME,
                    "+refs/heads/" + branch + ":" + remoteRef());
        }

        private String remoteRef() {
            return "refs/remotes/" + REMOTE_NAME + "/" + branch;
        }

        private void runGit(byte[] input, String... args) throws IOException {
            CommandResult result = runCommand(input, indexFile, gitArgs(args));
            if (result.exitCode() != 0) throw gitFailure(args, result);
        }

        private String runGitCapture(byte[] input, String... args) throws IOException {
            CommandResult result = runCommand(input, indexFile, gitArgs(args));
            if (result.exitCode() != 0) throw gitFailure(args, result);
            return result.output();
        }

        private void runGitWithoutIndex(byte[] input, String... args) throws IOException {
            CommandResult result = runCommand(input, null, gitArgs(args));
            if (result.exitCode() != 0) throw gitFailure(args, result);
        }

        private String[] gitArgs(String... args) {
            String[] command = new String[args.length + 2];
            command[0] = "git";
            command[1] = "--git-dir=" + gitDirectory;
            System.arraycopy(args, 0, command, 2, args.length);
            return command;
        }

        private CommandResult runCommand(byte[] input, Path index, String... command) throws IOException {
            CommandResult result = runCommandAllowFailure(input, index, command);
            if (result.exitCode() != 0) {
                throw new IOException("Command failed (" + result.exitCode() + "): " + String.join(" ", command)
                        + "\n" + result.output());
            }
            return result;
        }

        private CommandResult runCommandAllowFailure(byte[] input, Path index, String... command) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().put("GIT_SSH_COMMAND", sshCommand);
            if (index != null) builder.environment().put("GIT_INDEX_FILE", index.toString());
            Process process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) stdin.write(input);
            }
            byte[] output;
            try (InputStream stdout = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                stdout.transferTo(buffer);
                output = buffer.toByteArray();
            }
            try {
                int exitCode = process.waitFor();
                return new CommandResult(exitCode, new String(output, StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while running git command", e);
            }
        }

        private IOException gitFailure(String[] args, CommandResult result) {
            return new IOException("Git command failed (" + result.exitCode() + "): git "
                    + String.join(" ", Arrays.asList(args)) + "\n" + result.output());
        }

        private static String normalizeRepositoryPath(String path) {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("repository path is required");
            String normalized = path.replace('\\', '/');
            while (normalized.startsWith("/")) normalized = normalized.substring(1);
            if (normalized.isBlank() || normalized.contains("../") || normalized.equals("..")) {
                throw new IllegalArgumentException("invalid repository path: " + path);
            }
            return normalized;
        }

        private static String shellQuote(String value) {
            return "'" + value.replace("'", "'\\''") + "'";
        }

        private record CommandResult(int exitCode, String output) {
        }
    }
}
