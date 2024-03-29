package eu.wajja.web.fetcher.elasticsearch;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.wajja.web.fetcher.WebFetcherJob;

public class ElasticRestClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(ElasticRestClient.class);

	private List<String> hostnames;
	private String username;
	private String password;
	private String proxyScheme;
	private String proxyHostname;
	private Long proxyPort;
	private String proxyUsername;
	private String proxyPassword;

	public ElasticRestClient(List<String> hostnames, String username, String password, String proxyScheme, String proxyHostname, Long proxyPort, String proxyUsername, String proxyPassword) {

		this.hostnames = hostnames;
		this.username = username;
		this.password = password;
		this.proxyScheme = proxyScheme;
		this.proxyHostname = proxyHostname;
		this.proxyPort = proxyPort;
		this.proxyUsername = proxyUsername;
		this.proxyPassword = proxyPassword;
	}

	public RestHighLevelClient restHighLevelClient() {

		List<HttpHost> httpHosts = new ArrayList<>();
		CredentialsProvider credentialsProvider = new BasicCredentialsProvider();

		hostnames.stream().forEach(h -> {

            String scheme = null;
            String host = h;
            int port = -1;

            if (h.startsWith("http")) {
                scheme = host.split(":")[0];
                host = host.split(":")[1].substring(2);
            }

            if (host.contains(":")) {
                port = Integer.parseInt(host.split(":")[1]);
                host = host.split(":")[0];
            }

			try {

				HttpHost httpHost = new HttpHost(InetAddress.getByName(host), port, scheme);
				httpHosts.add(httpHost);

				if (username != null && password != null) {
					credentialsProvider.setCredentials(new AuthScope(httpHost), new UsernamePasswordCredentials(username, password));
				}

			} catch (NumberFormatException | UnknownHostException e) {
				LOGGER.error("Failed to find correct elastic node", e);
			}
		});

		RestClientBuilder restClientBuilder = RestClient.builder(httpHosts.toArray(new HttpHost[httpHosts.size()]));

		if (proxyHostname == null) {
			restClientBuilder = restClientBuilder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));

		} else {

			HttpHost proxyHost = new HttpHost(proxyHostname, proxyPort.intValue(), proxyScheme);
			restClientBuilder = restClientBuilder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setProxy(proxyHost).setDefaultCredentialsProvider(credentialsProvider));

			if (proxyUsername != null && proxyPassword != null) {
				credentialsProvider.setCredentials(new AuthScope(proxyHost), new UsernamePasswordCredentials(proxyUsername, proxyPassword));
			}
		}

		return new RestHighLevelClient(restClientBuilder);
	}
}
