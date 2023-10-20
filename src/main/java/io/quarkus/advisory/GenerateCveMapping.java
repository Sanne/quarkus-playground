package io.quarkus.advisory;

import static io.quarkus.advisory.MessageWriter.info;
import static io.quarkus.advisory.MessageWriter.warn;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "cve-mapping", mixinStandardHelpOptions = true)
public class GenerateCveMapping implements Runnable {

    @CommandLine.Option(names = { "-t",
            "--jira-token" }, description = "The Personal Access Token for authenticating with the JIRA server, could also be set using environment variable JIRA_TOKEN")
    private String jiraToken;

    @CommandLine.Option(names = { "-s",
            "--jira-server" }, description = "The JIRA server to connect to", defaultValue = "https://issues.redhat.com")
    private String jiraServerURL;

    @CommandLine.Option(names = {
            "--jira-version" }, description = "The target fixVersion of the project in Jira", required = true)
    private String jiraVersion;

    @CommandLine.Option(names = { "--bom" }, description = "Quarkus platform BOM", required = true)
    private String bom;

    @CommandLine.Option(names = {
            "--output-file" }, description = "If specified, this parameter will cause the output to be written to the path specified, instead of writing to the console.")
    public Path outputFile;

    @Override
    public void run() {

        final Map<String, List<ArtifactCoords>> vulnerableMapping = JiraReader.readVulnerableArtifacts(jiraServerURL,
                getJiraToken(), "QUARKUS", jiraVersion);

        var bomParts = bom.split(":");
        if (bomParts.length < 3) {
            throw new IllegalArgumentException("Failed to extract groupId:artifactId:version from " + bom);
        }
        final String bomGroupId = bomParts[0];
        final String bomArtifactId = bomParts[1];
        final String bomVersion = bomParts[bomParts.length - 1];
        final Map<ArtifactKey, ManifestComponent> components = ManifestProvider.getManifestComponents(bomGroupId, bomArtifactId,
                bomVersion);

        final Map<String, List<String>> cveMapping = new TreeMap<>();
        for (Map.Entry<String, List<ArtifactCoords>> vulnerableArtifacts : vulnerableMapping.entrySet()) {
            var vulnerableCoords = vulnerableArtifacts.getValue();
            if (vulnerableCoords.isEmpty()) {
                continue;
            }
            final String cveId = vulnerableArtifacts.getKey();
            final Set<String> purls = new HashSet<>(vulnerableCoords.size());
            for (ArtifactCoords vulnerableArtifact : vulnerableCoords) {
                var fixedComponent = components.get(vulnerableArtifact.getKey());
                if (fixedComponent == null) {
                    info("  Component " + vulnerableArtifact.toCompactCoords() + " was removed");
                } else {
                    var fixedVersions = new ArrayList<>(fixedComponent.getVersions());
                    if (fixedVersions.remove(vulnerableArtifact.getVersion())) {
                        warn("  Component " + vulnerableArtifact + " is still present in " + jiraVersion);
                        continue;
                    }
                    for (var c : vulnerableCoords) {
                        fixedVersions.remove(c.getVersion());
                    }
                    if (!fixedVersions.isEmpty()) {
                        info("  Component " + vulnerableArtifact + " was replaced with " + fixedComponent.getKey() + ":"
                                + fixedVersions);
                        for (String v : fixedVersions) {
                            purls.add(toPurl(fixedComponent.getKey(), v).toString());
                        }
                    } else {
                        info("  Component " + vulnerableArtifact.toCompactCoords() + " was removed");
                    }
                }
            }
            if (!purls.isEmpty()) {
                var purlList = new ArrayList<>(purls);
                Collections.sort(purlList);
                cveMapping.put(cveId, purlList);
            }
        }

        generateAdvisoryNodeV2(cveMapping);
    }

    /**
     * Embeds CVE mappings in the advisory notes
     */
    private void generateAdvisoryNodeV2(Map<String, List<String>> cveMapping) {
        var root = JsonNodeFactory.instance.objectNode();

        var bomCoords = ArtifactCoords.fromString(bom);
        var manifest = root.putObject("manifest");
        var refs = manifest.putArray("refs");
        var ref = refs.addObject();
        ref.put("type", "purl");
        ref.put("uri", "pkg:maven/"
                + bomCoords.getGroupId() + "/"
                + bomCoords.getArtifactId() + "@"
                + bomCoords.getVersion()
                + "?type=pom");

        if (!cveMapping.isEmpty()) {
            var simpleMapper = root.putObject("simple-mapper");
            refs = simpleMapper.putArray("refs");
            ref = refs.addObject();
            ref.put("type", "cve-component-mapping");
            ref.put("version", "0.0.2");
            var fix = ref.putObject("fix");

            for (Map.Entry<String, List<String>> e : cveMapping.entrySet()) {
                var arr = fix.putArray(e.getKey());
                for (String purl : e.getValue()) {
                    arr.add(purl);
                }
            }
        }

        try (Writer writer = getResultWriter()) {
            prettyPrint(writer, root);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Writer getResultWriter() throws IOException {
        if (outputFile != null) {
            if (Files.isDirectory(outputFile)) {
                throw new IllegalArgumentException(outputFile + " appears to be a directory");
            }
            var absolutePath = outputFile.normalize().toAbsolutePath();
            var parentDir = absolutePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            info("Saving result in " + absolutePath);
            return Files.newBufferedWriter(outputFile);
        }
        final StringWriter sw = new StringWriter();
        return new BufferedWriter(sw) {
            boolean close;

            @Override
            public void close() throws IOException {
                if (!close) {
                    close = true;
                    super.close();
                    info("ADVISORY NOTE:");
                    info(sw.getBuffer().toString());
                }
            }
        };
    }

    private static void prettyPrint(Writer writer, ObjectNode o) throws IOException {
        var prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        SbomerResponse.getMapper().writer(prettyPrinter).writeValue(writer, o);
    }

    private static PackageURL toPurl(ArtifactKey key, String version) {
        final TreeMap<String, String> qualifiers = new TreeMap<>();
        qualifiers.put("type", key.getType());
        if (!key.getClassifier().isEmpty()) {
            qualifiers.put("classifier", key.getClassifier());
        }
        try {
            return new PackageURL(PackageURL.StandardTypes.MAVEN,
                    key.getGroupId(),
                    key.getArtifactId(),
                    version,
                    qualifiers, null);
        } catch (MalformedPackageURLException e) {
            throw new RuntimeException("Failed to generate Purl for " + key + ":" + version, e);
        }
    }

    private String getJiraToken() {
        if (jiraToken != null) {
            return jiraToken;
        }
        jiraToken = System.getenv("JIRA_TOKEN");
        if (jiraToken == null) {
            throw new IllegalArgumentException("Neither --jira-token nor environment variable JIRA_TOKEN have been set");
        }
        return jiraToken;
    }
}