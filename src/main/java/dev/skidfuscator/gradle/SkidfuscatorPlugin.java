package dev.skidfuscator.gradle;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import dev.skidfuscator.dependanalysis.DependencyAnalyzer;
import dev.skidfuscator.dependanalysis.DependencyResult;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.process.ExecOperations;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public abstract class SkidfuscatorPlugin implements Plugin<Project> {

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract JavaToolchainService getJavaToolchainService();

    @Override
    public void apply(@NotNull Project project) {
        this.addExclude(project);

        NamedDomainObjectContainer<TransformerSpec> transformerContainer =
                project.getObjects().domainObjectContainer(TransformerSpec.class, TransformerSpec::new);

        SkidfuscatorExtension extension = project.getExtensions().create("skidfuscator", SkidfuscatorExtension.class, transformerContainer);

        project.afterEvaluate(p -> {
            Jar jarTask = p.getTasks().withType(Jar.class).findByName("jar");
            Jar shadowJarTask = p.getTasks().withType(Jar.class).findByName("shadowJar");
            Jar finalTask = (shadowJarTask != null) ? shadowJarTask : jarTask;

            if (finalTask == null) {
                project.getLogger().lifecycle("No jar or shadowJar task found. Skidfuscator will not run automatically.");
                return;
            }

            final File buildDir = p.getLayout().getBuildDirectory().get().getAsFile();
            final File projectDir = p.getProjectDir();
            final List<File> compileClasspathDependencies = p.getConfigurations().getByName("compileClasspath")
                    .getResolvedConfiguration()
                    .getResolvedArtifacts()
                    .stream()
                    .map(ResolvedArtifact::getFile)
                    .collect(Collectors.toList());
            final SkidfuscatorExecutionConfig executionConfig = snapshotExtension(extension);

            Task runSkidfuscator = p.getTasks().create("runSkidfuscator", task -> {
                task.dependsOn(finalTask);
                task.doLast(t -> {
                    File depsDir = new File(buildDir, "skidfuscator/dependencies");
                    collectDependencies(compileClasspathDependencies, depsDir, t.getLogger());

                    File skidDir = new File(buildDir, "skidfuscator");
                    if (!skidDir.exists()) {
                        skidDir.mkdirs();
                    }

                    String resolvedVersion;
                    try {
                        resolvedVersion = resolveVersion(executionConfig.skidfuscatorVersion);
                    } catch (IOException e) {
                        t.getLogger().error("Failed to fetch latest Skidfuscator version: {}", e.getMessage());
                        return;
                    }

                    File versionFile = new File(skidDir, ".version");
                    String currentVersion = readVersionFile(versionFile);

                    File skidJar = new File(projectDir, ".skidfuscator/skidfuscator-" + resolvedVersion + ".jar");
                    if (!skidJar.getParentFile().exists()) {
                        skidJar.getParentFile().mkdirs();
                    }
                    // If version changed or jar not present, re-download
                    if (!"dev".equalsIgnoreCase(resolvedVersion) && !resolvedVersion.equals(currentVersion) || !skidJar.exists()) {
                        t.getLogger().warn("Could not find Skidfuscator jar at " + skidJar.getAbsolutePath() + ", downloading...");
                        t.getLogger().lifecycle("Downloading Skidfuscator " + resolvedVersion + "...");
                        try {
                            downloadSkidfuscatorJar(resolvedVersion, skidJar);
                            writeVersionFile(versionFile, resolvedVersion);
                        } catch (IOException e) {
                            t.getLogger().error("Failed to download Skidfuscator: " + e.getMessage(), e);
                            return;
                        }
                    }

                    if (executionConfig.input == null || executionConfig.input.trim().isEmpty()) {
                        t.getLogger().lifecycle("No skidfuscator.input configured, skipping obfuscation.");
                        return;
                    }

                    File outputJar = new File(executionConfig.input);

                    if (!outputJar.exists()) {
                        t.getLogger().lifecycle("Output jar not found at " + outputJar.getAbsolutePath() + ", cannot run Skidfuscator.");
                        return;
                    }

                    // Add the dependencies directory as the single libs folder
                    List<String> effectiveLibs = new ArrayList<>(executionConfig.libs);
                    File[] copiedDependencies = depsDir.listFiles();
                    if (depsDir.exists() && copiedDependencies != null && copiedDependencies.length > 0) {
                        List<String> reduced;
                        final DependencyAnalyzer analyzer = new DependencyAnalyzer(
                                outputJar.toPath(),
                                depsDir.toPath()
                        );

                        try {
                            final DependencyResult result = analyzer.analyze();
                            for(DependencyResult.JarDependency jarDependency : result.getJarDependencies()) {
                                t.getLogger().lifecycle("JAR: " + jarDependency.getJarPath().getFileName());
                                t.getLogger().lifecycle("---------------------------------------------------");

                                for(DependencyResult.ClassDependency classDep : jarDependency.getClassesNeeded()) {
                                    t.getLogger().lifecycle("  Class: " + classDep.getClassName());

                                    for(String reason : classDep.getReasons()) {
                                        t.getLogger().lifecycle("    - " + reason);
                                    }
                                }

                                t.getLogger().lifecycle("");
                            }
                            t.getLogger().lifecycle("Reducing dependencies...");
                            reduced = result.getJarDependencies().stream()
                                    .map(DependencyResult.JarDependency::getJarPath)
                                    .map(Path::toString).collect(Collectors.toList());
                            t.getLogger().lifecycle("Reduced dependencies (" + reduced.size() + "):");
                        } catch (Exception e) {
                            t.getLogger().warn("Failed to minimize analyzed dependencies, falling back to full dependency set: " + e.getMessage(), e);
                            Arrays.stream(copiedDependencies)
                                    .map(File::getAbsolutePath)
                                    .forEach(effectiveLibs::add);
                            reduced = Collections.emptyList();
                        }

                        effectiveLibs.addAll(reduced);
                    }

                    File configFile = new File(skidDir, executionConfig.configFileName);
                    try {
                        writeHoconConfig(executionConfig, effectiveLibs, configFile);
                    } catch (IOException e) {
                        t.getLogger().error("Failed to write config file: " + e.getMessage(), e);
                        return;
                    }

                    File resultJar = (executionConfig.output != null && !executionConfig.output.trim().isEmpty())
                            ? new File(executionConfig.output)
                            : new File(outputJar.getParentFile(), outputJar.getName().replace(".jar", "-obf.jar"));

                    List<String> args = new ArrayList<>();
                    args.add("obfuscate");
                    args.add("-cfg"); args.add(configFile.getAbsolutePath());
                    args.add("-o"); args.add(resultJar.getAbsolutePath());
                    args.add("--debug");

                    if (executionConfig.phantom) args.add("-ph");
                    if (executionConfig.fuckit) args.add("-fuckit");
                    if (executionConfig.debug) args.add("-dbg");
                    if (executionConfig.notrack) args.add("-notrack");

                    if (executionConfig.runtime != null && !executionConfig.runtime.trim().isEmpty()) {
                        File rt = new File(executionConfig.runtime);
                        if (rt.exists()) {
                            args.add("-rt");
                            args.add(rt.getAbsolutePath());
                        }
                    }

                    // Input jar last
                    args.add(outputJar.getAbsolutePath());

                    t.getLogger().lifecycle("Running Skidfuscator...");

                    final String javaExecutable;
                    try {
                        javaExecutable = resolveJavaExecutable(executionConfig, t.getLogger());
                    } catch (RuntimeException e) {
                        t.getLogger().error(e.getMessage(), e);
                        return;
                    }

                    getExecOperations().exec(spec -> {
                        spec.setExecutable(javaExecutable);
                        List<String> fullArgs = new ArrayList<>();
                        fullArgs.add("-jar");
                        fullArgs.add(skidJar.getAbsolutePath());
                        fullArgs.addAll(args);
                        spec.setArgs(fullArgs);
                        spec.setIgnoreExitValue(false);
                    });

                    t.getLogger().lifecycle("Skidfuscation complete! Obfuscated jar at " + resultJar.getAbsolutePath());
                });
            });

            finalTask.finalizedBy(runSkidfuscator);
        });
    }

    private SkidfuscatorExecutionConfig snapshotExtension(SkidfuscatorExtension extension) {
        Map<String, Object> transformerMap = new HashMap<>();
        extension.getTransformers().getTransformers().forEach(spec ->
                transformerMap.put(spec.getName(), new HashMap<>(spec.getProperties()))
        );

        return new SkidfuscatorExecutionConfig(
                new ArrayList<>(extension.getExempt().getOrElse(Collections.emptyList())),
                new ArrayList<>(extension.getExclude().getOrElse(Collections.emptyList())),
                new ArrayList<>(extension.getLibs().getOrElse(Collections.emptyList())),
                transformerMap,
                extension.getPhantom().getOrElse(false),
                extension.getFuckit().getOrElse(false),
                extension.getDebug().getOrElse(false),
                extension.getNotrack().getOrElse(false),
                extension.getRuntime().getOrNull(),
                extension.getInput().getOrNull(),
                extension.getOutput().getOrNull(),
                extension.getConfigFileName().getOrElse("skidfuscator.conf"),
                extension.getSkidfuscatorVersion().getOrElse("latest"),
                extension.getJavaVersion().getOrNull(),
                extension.getJavaExecutable().getOrElse("java")
        );
    }

    private String resolveJavaExecutable(SkidfuscatorExecutionConfig executionConfig, org.gradle.api.logging.Logger logger) {
        if (executionConfig.javaVersion != null) {
            final Integer javaVersion = executionConfig.javaVersion;
            try {
                JavaLauncher launcher = getJavaToolchainService().launcherFor(spec ->
                        spec.getLanguageVersion().set(JavaLanguageVersion.of(javaVersion))
                ).get();

                String executable = launcher.getExecutablePath().getAsFile().getAbsolutePath();
                logger.lifecycle("Using Java toolchain version " + javaVersion + " for Skidfuscator.");
                return executable;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve Java toolchain version " + javaVersion + " for Skidfuscator.", e);
            }
        }

        if (executionConfig.javaExecutable != null && !executionConfig.javaExecutable.trim().isEmpty()) {
            return executionConfig.javaExecutable;
        }

        return "java";
    }

    private void collectDependencies(List<File> dependencies, File depsDir, org.gradle.api.logging.Logger logger) {
        if (!depsDir.exists() && !depsDir.mkdirs()) {
            logger.warn("Failed to create dependency directory: {}", depsDir.getAbsolutePath());
            return;
        }

        File[] existingFiles = depsDir.listFiles();
        if (existingFiles != null) {
            Arrays.stream(existingFiles).forEach(File::delete);
        }

        logger.lifecycle("Initial dependencies collected (" + dependencies.size() + "):");
        dependencies.forEach(dep -> logger.lifecycle(" - " + dep.getAbsolutePath()));

        for (File dep : dependencies) {
            try {
                File destFile = new File(depsDir, dep.getName());
                Files.copy(dep.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.warn("Failed to copy dependency: " + dep.getName(), e);
            }
        }
    }

    private String resolveVersion(String requestedVersion) throws IOException {
        if (!"latest".equalsIgnoreCase(requestedVersion)) {
            return requestedVersion;
        }

        URL url = new URL("https://api.github.com/repos/skidfuscatordev/skidfuscator-java-obfuscator/releases/latest");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.connect();
        if (conn.getResponseCode() != 200) {
            throw new IOException("Failed to fetch latest release info. HTTP " + conn.getResponseCode());
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String json = br.lines().collect(Collectors.joining());
            int tagIndex = json.indexOf("\"tag_name\"");
            if (tagIndex == -1) {
                throw new IOException("Could not find tag_name in release JSON");
            }
            int start = json.indexOf(":", tagIndex) + 1;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            String tag = json.substring(start, end).replaceAll("\"", "").trim();
            return tag.startsWith("v") ? tag.substring(1) : tag;
        }
    }

    private void downloadSkidfuscatorJar(String version, File target) throws IOException {
        String urlStr = "https://github.com/skidfuscatordev/skidfuscator-java-obfuscator/releases/download/" + version + "/skidfuscator.jar";
        URL url = new URL(urlStr);
        try (InputStream in = url.openStream(); OutputStream out = Files.newOutputStream(target.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private void writeHoconConfig(SkidfuscatorExecutionConfig executionConfig, List<String> libs, File configFile) throws IOException {
        Config config = buildConfig(executionConfig, libs);
        String rendered = config.root().render(
            ConfigRenderOptions.defaults()
                .setComments(false)
                .setJson(false)
                .setOriginComments(false)
        );

        try (FileWriter fw = new FileWriter(configFile)) {
            fw.write(rendered);
        }
    }

    private Config buildConfig(SkidfuscatorExecutionConfig executionConfig, List<String> libs) {
        Map<String, Object> rootMap = new HashMap<>();
        rootMap.put("exempt", executionConfig.exempt);
        rootMap.put("exclude", executionConfig.exclude);
        rootMap.put("libraries", libs);
        rootMap.putAll(executionConfig.transformers);

        // Parse the map into a Config
        return ConfigFactory.parseMap(rootMap);
    }

    private static final class SkidfuscatorExecutionConfig {
        private final List<String> exempt;
        private final List<String> exclude;
        private final List<String> libs;
        private final Map<String, Object> transformers;
        private final boolean phantom;
        private final boolean fuckit;
        private final boolean debug;
        private final boolean notrack;
        private final String runtime;
        private final String input;
        private final String output;
        private final String configFileName;
        private final String skidfuscatorVersion;
        private final Integer javaVersion;
        private final String javaExecutable;

        private SkidfuscatorExecutionConfig(
                List<String> exempt,
                List<String> exclude,
                List<String> libs,
                Map<String, Object> transformers,
                boolean phantom,
                boolean fuckit,
                boolean debug,
                boolean notrack,
                String runtime,
                String input,
                String output,
                String configFileName,
                String skidfuscatorVersion,
                Integer javaVersion,
                String javaExecutable
        ) {
            this.exempt = exempt;
            this.exclude = exclude;
            this.libs = libs;
            this.transformers = transformers;
            this.phantom = phantom;
            this.fuckit = fuckit;
            this.debug = debug;
            this.notrack = notrack;
            this.runtime = runtime;
            this.input = input;
            this.output = output;
            this.configFileName = configFileName;
            this.skidfuscatorVersion = skidfuscatorVersion;
            this.javaVersion = javaVersion;
            this.javaExecutable = javaExecutable;
        }
    }

    private String readVersionFile(File versionFile) {
        if (!versionFile.exists()) return "";
        try (BufferedReader br = new BufferedReader(new FileReader(versionFile))) {
            return br.readLine().trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void writeVersionFile(File versionFile, String version) {
        try (FileWriter fw = new FileWriter(versionFile)) {
            fw.write(version);
        } catch (IOException ignored) {}
    }

    private void addExclude(final Project project) {
        // Add gitignore handling at plugin application time
        File gitignore = new File(project.getRootDir(), ".gitignore");
        try {
            // Check if .skidfuscator is already in .gitignore
            boolean needsEntry = true;
            if (gitignore.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(gitignore))) {
                    if (reader.lines().anyMatch(line -> line.trim().equals(".skidfuscator"))) {
                        needsEntry = false;
                    }
                }
            }

            // Append .skidfuscator to .gitignore if needed
            if (needsEntry) {
                try (FileWriter writer = new FileWriter(gitignore, true)) {
                    // Add newline if file exists and doesn't end with one
                    if (gitignore.exists() && gitignore.length() > 0) {
                        String content = new String(java.nio.file.Files.readAllBytes(gitignore.toPath()));
                        if (!content.endsWith("\n")) {
                            writer.write("\n");
                        }
                    }
                    writer.write(".skidfuscator\n");
                }
                project.getLogger().lifecycle("Added .skidfuscator to .gitignore");
            }
        } catch (IOException e) {
            project.getLogger().warn("Failed to update .gitignore: " + e.getMessage());
        }
    }
}
