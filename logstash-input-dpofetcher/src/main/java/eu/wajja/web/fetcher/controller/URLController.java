package eu.wajja.web.fetcher.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLException;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.wajja.web.fetcher.elasticsearch.ElasticSearchService;
import eu.wajja.web.fetcher.model.Result;

public class URLController {

    private static final Logger LOGGER = LoggerFactory.getLogger(URLController.class);

    private Proxy proxy;
    private Long timeout;
    private String userAgent;
    private String referer;
    private String waitForCssSelector;
    private Integer maxWaitForCssSelector;
    private ElasticSearchService elasticSearchService;
    private WebDriverController webDriverController = new WebDriverController();

    public URLController(ElasticSearchService elasticSearchService, Proxy proxy, Long timeout, String userAgent, String referer, String waitForCssSelector, Integer maxWaitForCssSelector) {

        this.proxy = proxy;
        this.timeout = timeout;
        this.userAgent = userAgent;
        this.referer = referer;
        this.waitForCssSelector = waitForCssSelector;
        this.maxWaitForCssSelector = maxWaitForCssSelector;
        this.elasticSearchService = elasticSearchService;
    }

    public Result getURL(String index, String currentUrl, String initialUrl, String chromeDriver, boolean isJson) {

        return getURL(index, currentUrl, initialUrl, chromeDriver, new HashSet<>(), isJson);
    }

    private Result getURL(String index, String currentUrl, String initialUrl, String chromeDriver, Set<String> redirectUrls, boolean isJson) {

        Result result = new Result();
        result.setUrl(currentUrl);
        result.setRootUrl(initialUrl);
        result.setCode(404);
        result.setRedirectUrls(redirectUrls);

        HttpURLConnection httpURLConnection = null;

        try {

            URL url = this.createUrl(currentUrl);

            if (proxy == null) {
                httpURLConnection = (HttpURLConnection) url.openConnection();
            } else {
                httpURLConnection = (HttpURLConnection) url.openConnection(proxy);
            }

            httpURLConnection.setConnectTimeout(timeout.intValue());
            httpURLConnection.setReadTimeout(timeout.intValue());
            httpURLConnection.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
            httpURLConnection.addRequestProperty("User-Agent", userAgent);
            httpURLConnection.addRequestProperty("Referer", referer);
            httpURLConnection.setRequestMethod("HEAD");

            if (isJson) {
                httpURLConnection.addRequestProperty("Accept", "application/json, text/plain, */*");
            }

            httpURLConnection.connect();

            int code = httpURLConnection.getResponseCode();
            String message = httpURLConnection.getResponseMessage();

            result.setCode(code);
            result.setMessage(message);
            result.setContentType(parseContentType(httpURLConnection.getContentType()));

            if (code == HttpURLConnection.HTTP_OK) {

                if (elasticSearchService.existsInIndex(currentUrl, index)) {

                    result = elasticSearchService.getFromIndex(currentUrl, index);
                    result.setRedirectUrls(redirectUrls);
                    return result;
                }

                if (isJson) {

                    LOGGER.debug("Found pdf, downloading {}", currentUrl);
                    result.setHeaders(httpURLConnection.getHeaderFields());
                    result.setContent(downloadContent(currentUrl));

                } else {

                    result.setContentType("text/html");
                    closeConnection(httpURLConnection);
                    result.setHeaders(httpURLConnection.getHeaderFields());
                    byte[] bytes = webDriverController.getURL(result.getUrl(), chromeDriver, waitForCssSelector, maxWaitForCssSelector);
                    result.setContent(bytes);
                }

            } else if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == 307 || code == HttpURLConnection.HTTP_SEE_OTHER) {

                String newUrl = httpURLConnection.getHeaderField("Location");
                closeConnection(httpURLConnection);

                LOGGER.debug("Redirect needed to :  {}", newUrl);
                result.getRedirectUrls().add(currentUrl);
                return getURL(index, newUrl, initialUrl, chromeDriver, result.getRedirectUrls(), isJson);

            } else {
                LOGGER.warn("Failed To Read status {}, url {}, message {}", code, url, message);
            }

        } catch (SocketTimeoutException e) {

            LOGGER.warn("Thread url {}, sleeping and trying again", currentUrl);
            closeConnection(httpURLConnection);
            return result;

        } catch (SSLException e) {

            LOGGER.warn("Thread url {}, SSLException error, sleeping and trying again", currentUrl, e);
            closeConnection(httpURLConnection);
            return getURL(index, currentUrl, initialUrl, chromeDriver, isJson);

        } catch (MalformedURLException mue) {
            LOGGER.error("Malformed url : {}", currentUrl, mue);
            closeConnection(httpURLConnection);
            return result;

        } catch (Exception e) {
            LOGGER.error("Failed to retrieve URL url {}", currentUrl, e);
        } finally {
            closeConnection(httpURLConnection);
        }

        return result;
    }

    private boolean isSameDocument(HttpURLConnection httpURLConnection, Result result) {

        int length = httpURLConnection.getContentLength();
        String eTag = httpURLConnection.getHeaderField("ETag");

        if (eTag != null && result.geteTag() != null && result.geteTag().equals(eTag)) {
            return true;

        } else if (length > 0 && result.getLength() != null && result.getLength().equals(length)) {
            return true;

        } else {

            result.setLength(length);
            result.seteTag(eTag);

            return false;
        }

    }

    private String parseContentType(String contentType) {

        if (contentType == null || contentType.isEmpty()) {
            return "application/octet-stream";
        }

        if (contentType.contains(";")) {
            contentType = contentType.substring(0, contentType.indexOf(';'));
        }

        contentType = contentType.replace("\"", "");

        return contentType.trim().toLowerCase();
    }

    private byte[] downloadContent(String currentUrl) {

        HttpURLConnection httpURLConnection = null;

        try {

            URL url = this.createUrl(currentUrl);

            if (proxy == null) {
                httpURLConnection = (HttpURLConnection) url.openConnection();
            } else {
                httpURLConnection = (HttpURLConnection) url.openConnection(proxy);
            }

            httpURLConnection.setConnectTimeout(timeout.intValue());
            httpURLConnection.setReadTimeout(timeout.intValue());
            httpURLConnection.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
            httpURLConnection.addRequestProperty("User-Agent", userAgent);
            httpURLConnection.addRequestProperty("Referer", referer);

            httpURLConnection.connect();

            try (InputStream inputStream = httpURLConnection.getInputStream()) {
                return IOUtils.toByteArray(inputStream);
            }

        } catch (SocketTimeoutException e) {

            LOGGER.warn("Thread url {}, sleeping and trying again", currentUrl);
            closeConnection(httpURLConnection);

        } catch (SSLException e) {

            LOGGER.warn("Thread url {}, SSLException error, sleeping and trying again", currentUrl, e);
            closeConnection(httpURLConnection);

        } catch (MalformedURLException mue) {
            LOGGER.error("Malformed url : {}", currentUrl, mue);
            closeConnection(httpURLConnection);

        } catch (Exception e) {
            LOGGER.error("Failed to retrieve URL url {}", currentUrl, e);
        } finally {
            closeConnection(httpURLConnection);
        }

        return null;

    }

    private void closeConnection(HttpURLConnection httpURLConnection) {

        if (httpURLConnection != null) {
            httpURLConnection.disconnect();
        }
    }

    private byte[] getInputStream(HttpURLConnection httpURLConnection) throws IOException {

        try (InputStream inputStream = httpURLConnection.getInputStream()) {
            return IOUtils.toByteArray(inputStream);
        } catch (IOException e) {
            LOGGER.error("Failed to retrive url {}", httpURLConnection.getURL().toString(), e);
        }

        return new byte[0];
    }

    public URL createUrl(String currentUrl) throws MalformedURLException {

        return new URL(currentUrl);
    }

    public void setTimeout(Long timeOut) {

        this.timeout = timeOut;
    }

}