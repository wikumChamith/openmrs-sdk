package org.openmrs.maven.plugins.utility;

import org.apache.maven.plugin.MojoExecutionException;
import org.openmrs.maven.plugins.model.Artifact;
import org.twdata.maven.mojoexecutor.MojoExecutor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

/**
 * The purpose of this class is to handle all interactions with Maven that require retrieving artifacts from the Maven repository
 */
public class ArtifactHelper {

	final MavenEnvironment mavenEnvironment;

	public ArtifactHelper(MavenEnvironment mavenEnvironment) {
		this.mavenEnvironment = mavenEnvironment;
	}

	/**
	 * Downloads the given artifact to the given directory with the given fileName.  If fileName is null, it will use the maven default.
	 * @param artifact the artifact to download
	 * @param directory the directory into which to download the artifact
	 * @param unpack if true will unzip the artifact in the given directory, otherwise will not unpack it
	 * @throws MojoExecutionException if there are errors
	 */
	public void downloadArtifact(Artifact artifact, File directory, boolean unpack) throws MojoExecutionException {
		try (TempDirectory markersDirectory = TempDirectory.create("markers")) {

			String goal = "copy";
			List<MojoExecutor.Element> configuration = new ArrayList<>();
			configuration.add(element("artifactItems", artifact.toElement(directory.getAbsolutePath())));
			configuration.add(element("overWriteSnapshots", "true"));
			configuration.add(element("overWriteReleases", "true"));
			if (unpack) {
				goal = "unpack";
				configuration.add(element("markersDirectory", markersDirectory.getAbsolutePath()));
			}

			executeMojo(
					plugin(
							groupId(SDKConstants.DEPENDENCY_PLUGIN_GROUP_ID),
							artifactId(SDKConstants.DEPENDENCY_PLUGIN_ARTIFACT_ID),
							version(SDKConstants.DEPENDENCY_PLUGIN_VERSION)
					),
					goal(goal),
					configuration(configuration.toArray(new MojoExecutor.Element[0])),
					executionEnvironment(
							mavenEnvironment.getMavenProject(),
							mavenEnvironment.getMavenSession(),
							mavenEnvironment.getPluginManager()
					)
			);
		}
	}

	/**
	 * Verifies the GPG signatures of the given already-downloaded artifacts by delegating to the
	 * openmrs-pgpverify-maven-plugin. The plugin owns the trust check: it only verifies artifacts in
	 * its whitelisted groups (org.openmrs by default), resolves each .asc, and fails the build on an
	 * invalid signature. Missing signatures and non-whitelisted artifacts are tolerated.
	 *
	 * @param artifacts the artifacts to verify
	 * @param directory the directory the artifacts were downloaded into
	 */
	public void verifySignatures(List<Artifact> artifacts, File directory) throws MojoExecutionException {
		if (artifacts == null) {
			return;
		}
		List<Artifact> openmrsArtifacts = new ArrayList<>();
		for (Artifact artifact : artifacts) {
			if (isOpenmrsArtifact(artifact)) {
				openmrsArtifacts.add(artifact);
			}
		}
		if (openmrsArtifacts.isEmpty()) {
			return;
		}
		executeMojo(
				plugin(
						groupId(SDKConstants.PGPVERIFY_PLUGIN_GROUP_ID),
						artifactId(SDKConstants.PGPVERIFY_PLUGIN_ARTIFACT_ID),
						version(SDKConstants.PGPVERIFY_PLUGIN_VERSION)
				),
				goal("verify-files"),
				configuration(
						verifyFilesArtifacts(openmrsArtifacts, directory),
						element("failOnMissingSignature", "false"),
						element("signatureRepository", SDKConstants.OPENMRS_SIGNATURE_REPOSITORY),
						element("keyServer", "https://keyserver.ubuntu.com"),
						element("keyRings", element("keyRing", signingKeyRing().getAbsolutePath()))
				),
				executionEnvironment(
						mavenEnvironment.getMavenProject(),
						mavenEnvironment.getMavenSession(),
						mavenEnvironment.getPluginManager()
				)
		);
	}

	private static final String SIGNING_KEY_RESOURCE = "openmrs-signing-key.asc";

	private static volatile File signingKeyRingFile;

	/**
	 * Extracts the bundled OpenMRS public key to a temp file so it can be passed as a keyRing. The
	 * plugin checks the ring before contacting the key server, so the common path verifies offline
	 * and only a rotated key not in the ring falls back to the (explicitly configured) key server.
	 */
	private static File signingKeyRing() throws MojoExecutionException {
		File file = signingKeyRingFile;
		if (file == null || !file.exists()) {
			synchronized (ArtifactHelper.class) {
				file = signingKeyRingFile;
				if (file == null || !file.exists()) {
					try (InputStream in = ArtifactHelper.class.getClassLoader().getResourceAsStream(SIGNING_KEY_RESOURCE)) {
						if (in == null) {
							throw new MojoExecutionException("Missing bundled signing key resource: " + SIGNING_KEY_RESOURCE);
						}
						File tmp = File.createTempFile("openmrs-signing-key", ".asc");
						tmp.deleteOnExit();
						Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
						signingKeyRingFile = tmp;
						file = tmp;
					}
					catch (IOException e) {
						throw new MojoExecutionException("Failed to extract bundled signing key", e);
					}
				}
			}
		}
		return file;
	}

	static boolean isOpenmrsArtifact(Artifact artifact) {
		String groupId = artifact.getGroupId();
		return groupId != null
				&& (groupId.equals(Artifact.GROUP_OPENMRS) || groupId.startsWith(Artifact.GROUP_OPENMRS + "."));
	}

	static MojoExecutor.Element verifyFilesArtifacts(List<Artifact> artifacts, File directory) {
		List<MojoExecutor.Element> items = new ArrayList<>();
		for (Artifact artifact : artifacts) {
			items.add(artifactItem(artifact, new File(directory, artifact.getDestFileName())));
		}
		return element("artifacts", items.toArray(new MojoExecutor.Element[0]));
	}

	static MojoExecutor.Element artifactItem(Artifact artifact, File artifactFile) {
		List<MojoExecutor.Element> item = new ArrayList<>();
		item.add(element("file", artifactFile.getAbsolutePath()));
		item.add(element("groupId", artifact.getGroupId()));
		item.add(element("artifactId", artifact.getArtifactId()));
		item.add(element("version", artifact.getVersion()));
		item.add(element("type", artifact.getType()));
		if (artifact.getClassifier() != null) {
			item.add(element("classifier", artifact.getClassifier()));
		}
		return element("artifact", item.toArray(new MojoExecutor.Element[0]));
	}
}