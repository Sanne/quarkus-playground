package io.quarkus.bom.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

import io.quarkus.bom.PomResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;

public class PlatformBomConfig {

	public static PlatformBomConfig forPom(PomResolver pomResolver) {
		return build(pomResolver);
	}

	private static PlatformBomConfig build(PomResolver pomResolver) {
		Path pom = pomResolver.pomPath();
		try {
		    final Model model = pomResolver.readLocalModel(pom);
			final DependencyManagement dm = model.getDependencyManagement();
		    if(dm == null) {
		    	throw new Exception(pom + " does not include managed dependencies");
		    }

		    final Properties allProps = new Properties();
		    allProps.putAll(model.getProperties());
		    Parent parent = model.getParent();
			while (parent != null) {
				final String relativePath = parent.getRelativePath();
				if(relativePath == null || relativePath.isEmpty()) {
					break;
				}
				Path parentPom = pom.getParent().resolve(relativePath).normalize().toAbsolutePath();
				final Model parentModel = pomResolver.readLocalModel(parentPom);
				if(parentModel == null) {
					break;
				}
				allProps.putAll(parentModel.getProperties());
				parent = parentModel.getParent();
				pom = parentPom;
			}
			return fromManagedDeps(pomResolver, new DefaultArtifact(ModelUtils.getGroupId(model),
					model.getArtifactId(), null, "pom", ModelUtils.getVersion(model)),
					dm.getDependencies(), allProps);
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize platform BOM config", e);
		}
	}

	private static PlatformBomConfig fromManagedDeps(PomResolver bomResolver, Artifact bomArtifact, final List<Dependency> managedDeps, Properties props) {
		final PlatformBomConfig config = new PlatformBomConfig();
		config.bomResolver = bomResolver;
		config.bomArtifact = Objects.requireNonNull(bomArtifact);
		for(Dependency dep : managedDeps) {
			String version = dep.getVersion();
			if(version.startsWith("${") && version.endsWith("}")) {
				String prop = version.substring(2, version.length() - 1);
				String value = props.getProperty(prop);
				if(value != null) {
					version = value;
				}
			}
			final Artifact artifact = new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType(), version);
			if(config.quarkusBom == null && artifact.getArtifactId().equals("quarkus-bom") && artifact.getGroupId().equals("io.quarkus")) {
				config.quarkusBom = artifact;
			} else {
			    config.directDeps.add(artifact);
			}
		}
		if(config.quarkusBom == null) {
			throw new RuntimeException("Failed to locate io.quarkus:quarkus-bom among the dependencies");
		}
		return config;
	}

	private PomResolver bomResolver;
	private Artifact bomArtifact;
	private Artifact quarkusBom;
	private List<Artifact> directDeps = new ArrayList<>();

	private PlatformBomConfig() {
	}

	public PomResolver bomResolver() {
		return bomResolver;
	}

	public Artifact bomArtifact() {
		return bomArtifact;
	}

	public Artifact quarkusBom() {
		return quarkusBom;
	}

	public List<Artifact> directDeps() {
		return directDeps;
	}
}