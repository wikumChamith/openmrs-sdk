package org.openmrs.maven.plugins.utility;


import lombok.Getter;
import lombok.Setter;
import org.apache.maven.it.VerificationException;
import org.junit.Test;
import org.openmrs.maven.plugins.AbstractMavenIT;
import org.openmrs.maven.plugins.model.Artifact;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Getter @Setter
public class ArtifactHelperIT extends AbstractMavenIT {

	@Test
	public void downloadArtifact_shouldDownloadToSpecifiedDirectory() throws Exception {
		executeTest(() -> {
			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("idgen-omod", "4.14.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
			File downloadedArtifact = new File(getMavenTestDirectory(), "idgen-4.14.0.jar");
			assertTrue(downloadedArtifact.exists());
		});
	}

	@Test
	public void downloadArtifact_shouldUnpackToSpecifiedDirectory() throws Exception {
		executeTest(() -> {
			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("idgen-omod", "4.14.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), true);
			File downloadedArtifact = new File(getMavenTestDirectory(), "idgen-4.14.0.jar");
			assertFalse(downloadedArtifact.exists());
			File liquibaseXml = new File(getMavenTestDirectory(), "liquibase.xml");
			assertTrue(liquibaseXml.exists());
			File configXml = new File(getMavenTestDirectory(), "config.xml");
			assertTrue(configXml.exists());
		});
	}

	@Test(expected = VerificationException.class)
	public void downloadArtifact_shouldFailIfNoArtifactIsFound() throws Exception {
		executeTest(() -> {
			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("idgen-omod", "4.0.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
		});
	}

	@Test
	public void verifySignatures_shouldPassForValidlySignedArtifact() throws Exception {
		executeTest(() -> {
			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("xforms-omod", "5.0.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
			artifactHelper.verifySignatures(Collections.singletonList(artifact), getMavenTestDirectory());
			assertTrue(new File(getMavenTestDirectory(), "xforms-5.0.0.jar").exists());
		});
	}

	@Test(expected = VerificationException.class)
	public void verifySignatures_shouldFailWhenSignedArtifactIsTampered() throws Exception {
		executeTest(() -> {
			ArtifactHelper artifactHelper = new ArtifactHelper(getMavenEnvironment());
			Artifact artifact = new Artifact("xforms-omod", "5.0.0", "org.openmrs.module", "jar");
			artifactHelper.downloadArtifact(artifact, getMavenTestDirectory(), false);
			File downloaded = new File(getMavenTestDirectory(), "xforms-5.0.0.jar");
			try (RandomAccessFile raf = new RandomAccessFile(downloaded, "rw")) {
				long middle = raf.length() / 2;
				raf.seek(middle);
				int b = raf.read();
				raf.seek(middle);
				raf.write(b ^ 0x01);
			}
			artifactHelper.verifySignatures(Collections.singletonList(artifact), getMavenTestDirectory());
		});
	}
}
