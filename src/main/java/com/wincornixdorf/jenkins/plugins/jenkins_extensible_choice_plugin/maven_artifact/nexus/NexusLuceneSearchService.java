package com.wincornixdorf.jenkins.plugins.jenkins_extensible_choice_plugin.maven_artifact.nexus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;

import org.sonatype.nexus.rest.model.NexusNGArtifact;
import org.sonatype.nexus.rest.model.NexusNGArtifactHit;
import org.sonatype.nexus.rest.model.NexusNGArtifactLink;
import org.sonatype.nexus.rest.model.NexusNGRepositoryDetail;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.wincornixdorf.jenkins.plugins.jenkins_extensible_choice_plugin.maven_artifact.IVersionReader;
import com.wincornixdorf.jenkins.plugins.jenkins_extensible_choice_plugin.maven_artifact.ValidAndInvalidClassifier;

public class NexusLuceneSearchService implements IVersionReader {

	private static final String LUCENE_SEARCH_SERVICE_URI = "service/local/lucene/search";

	private static final Logger LOGGER = Logger.getLogger(NexusLuceneSearchService.class.getName());

	private final String mURL;
	private final String mGroupId;
	private final String mArtifactId;
	private final String mPackaging;
	private final ValidAndInvalidClassifier mClassifier;

	private WebResource mInstance;

	public NexusLuceneSearchService(String pURL, String pGroupId, String pArtifactId, String pPackaging) {
		this(pURL, pGroupId, pArtifactId, pPackaging, ValidAndInvalidClassifier.getDefault());
	}

	public NexusLuceneSearchService(String pURL, String pGroupId, String pArtifactId, String pPackaging,
			ValidAndInvalidClassifier pAcceptedClassifier) {
		super();
		this.mURL = pURL;
		this.mGroupId = pGroupId;
		this.mArtifactId = pArtifactId;
		this.mPackaging = pPackaging;
		this.mClassifier = pAcceptedClassifier;
	}

	void init() {
		ClientConfig config = new DefaultClientConfig();
		Client client = Client.create(config);
		mInstance = client.resource(UriBuilder.fromUri(getURL()).build());
		mInstance = mInstance.path(LUCENE_SEARCH_SERVICE_URI);
		// String respAsString = service.path("nexus/service/local/lucene/search")
		// .queryParam("g", "com.wincornixdorf.pnc.releases").queryParam("a", "pnc-brass-maven")
		// .accept(MediaType.APPLICATION_XML).get(String.class);
		// System.out.println(respAsString);
		//
	}

	/**
	 * Seach in Nexus for the artifact using the Lucene Service.
	 * https://repository.sonatype.org/nexus-indexer-lucene-plugin/default/docs/path__lucene_search.html
	 */
	public List<String> retrieveVersions() {
		if (LOGGER.isLoggable(Level.FINE)) {
			LOGGER.fine("query nexus with arguments: r:" + mURL + ", g:" + getGroupId() + ", a:" + getArtifactId()
					+ ", p:" + getPackaging()+ ", c: " + getClassifier().toString());
		}

		MultivaluedMap<String, String> requestParams = new MultivaluedHashMap<String, String>();
		if (getGroupId() != "")
			requestParams.putSingle("g", getGroupId());
		if (getArtifactId() != "")
			requestParams.putSingle("a", getArtifactId());
		if (getPackaging() != "")
			requestParams.putSingle("p", getPackaging());
		if (getClassifier() != null) {
			// FIXME: There is of course a better way how to do it...
			final List<String> query = new ArrayList<String>();
			for (String current : getClassifier().getValid())
				query.add(current);

			if (!query.isEmpty())
				requestParams.put("c", query);
		}

		final PatchedSearchNGResponse xmlResult = getInstance().queryParams(requestParams)
				.accept(MediaType.APPLICATION_XML).get(PatchedSearchNGResponse.class);

		List<String> retVal = new ArrayList<String>();
		if (xmlResult == null) {
			LOGGER.info("response from Nexus is NULL.");
		} else if (xmlResult.getTotalCount() == 0) {
			LOGGER.info("response from Nexus does not contain any results.");
		} else {
			final Map<String, String> repoURLs = retrieveRepositoryURLs(xmlResult.getRepoDetails());

			// https://davis.wincor-nixdorf.com/nexus/content/repositories/wn-ps-us-pnc/com/wincornixdorf/pnc/releases/pnc-brass-maven/106/pnc-brass-maven-106.tar.gz
			for (NexusNGArtifact current : xmlResult.getData()) {
				final StringBuilder theBaseDownloadURL = new StringBuilder();
				// theDownloadURL.append(repoURL);
				theBaseDownloadURL.append("/");
				theBaseDownloadURL.append(current.getGroupId().replace(".", "/"));
				theBaseDownloadURL.append("/");
				theBaseDownloadURL.append(current.getArtifactId());
				theBaseDownloadURL.append("/");
				theBaseDownloadURL.append(current.getVersion());
				theBaseDownloadURL.append("/");
				theBaseDownloadURL.append(current.getArtifactId());
				theBaseDownloadURL.append("-");
				theBaseDownloadURL.append(current.getVersion());

				for (NexusNGArtifactHit currentHit : current.getArtifactHits()) {
					for (NexusNGArtifactLink currentLink : currentHit.getArtifactLinks()) {
						final String repo = repoURLs.get(currentHit.getRepositoryId());

						boolean addCurrentEntry = true;

						// if packaging configuration is set but does not match
						if (!"".equals(getPackaging()) && !getPackaging().equals(currentLink.getExtension())) {
							addCurrentEntry &= false;
						}

						// check the classifier. Can be explicit invalid, explicit valid 
						if (getClassifier().isInvalid(currentLink.getClassifier())) {
							addCurrentEntry &= false;
						} else if (!getClassifier().isValid(currentLink.getClassifier())) {
							addCurrentEntry &= false;
						} else {
							// yes, possible if something is not explicit invalid and not explicit valid
						}

						if (addCurrentEntry) {
							final String classifier = (currentLink.getClassifier() == null ? ""
									: "-" + currentLink.getClassifier());
							retVal.add(repo + theBaseDownloadURL.toString() + classifier + "."
									+ currentLink.getExtension());
						}
					}
				}
			}

		}
		return retVal;
	}

	Map<String, String> retrieveRepositoryURLs(final List<NexusNGRepositoryDetail> pRepoDetails) {
		Map<String, String> retVal = new HashMap<String, String>();

		for (NexusNGRepositoryDetail currentRepo : pRepoDetails) {
			String theURL = currentRepo.getRepositoryURL();

			// FIXME: Repository URL can be retrieved somehow...
			theURL = theURL.replace("service/local", "content");
			retVal.put(currentRepo.getRepositoryId(), theURL);
		}
		return retVal;
	}

	public String getURL() {
		return mURL;
	}

	public String getGroupId() {
		return mGroupId;
	}

	public String getArtifactId() {
		return mArtifactId;
	}

	public String getPackaging() {
		return mPackaging;
	}

	public ValidAndInvalidClassifier getClassifier() {
		return mClassifier;
	}

	WebResource getInstance() {
		if (mInstance == null) {
			init();
		}
		return mInstance;
	}
}
