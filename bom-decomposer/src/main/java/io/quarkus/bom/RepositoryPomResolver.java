package io.quarkus.bom;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;

public class RepositoryPomResolver implements PomResolver {

	private final Artifact pomArtifact;

	public RepositoryPomResolver(Artifact pomArtifact) {
		this.pomArtifact = pomArtifact;
	}

	@Override
	public Path pomPath() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String source() {
		return pomArtifact.toString();
	}

	@Override
	public Model readLocalModel(Path pom) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Artifact pomArtifact() {
		return pomArtifact;
	}
}