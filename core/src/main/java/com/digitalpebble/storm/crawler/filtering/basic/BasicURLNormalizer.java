/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.storm.crawler.filtering.basic;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.digitalpebble.storm.crawler.Metadata;
import com.digitalpebble.storm.crawler.filtering.URLFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.apache.commons.lang.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicURLNormalizer implements URLFilter {

    private static final Logger LOG = LoggerFactory
            .getLogger(BasicURLNormalizer.class);
    /**
     * Nutch 1098 - finds URL encoded parts of the URL
     */
    private final static Pattern unescapeRulePattern = Pattern
            .compile("%([0-9A-Fa-f]{2})");

    // charset used for encoding URLs before escaping
    private final static Charset utf8 = Charset.forName("UTF-8");

    /** look-up table for characters which should not be escaped in URL paths */
    private final static boolean[] unescapedCharacters = new boolean[128];

    static {
        for (int c = 0; c < 128; c++) {
            /*
             * https://tools.ietf.org/html/rfc3986#section-2.2 For consistency,
             * percent-encoded octets in the ranges of ALPHA (%41-%5A and
             * %61-%7A), DIGIT (%30-%39), hyphen (%2D), period (%2E), underscore
             * (%5F), or tilde (%7E) should not be created by URI producers and,
             * when found in a URI, should be decoded to their corresponding
             * unreserved characters by URI normalizers.
             */
            if ((0x41 <= c && c <= 0x5A) || (0x61 <= c && c <= 0x7A)
                    || (0x30 <= c && c <= 0x39) || c == 0x2D || c == 0x2E
                    || c == 0x5F || c == 0x7E) {
                unescapedCharacters[c] = true;
            } else {
                unescapedCharacters[c] = false;
            }
        }
    }

    boolean removeAnchorPart = true;
    boolean unmangleQueryString = true;
    boolean checkValidURI = true;
    final Set<String> queryElementsToRemove = new TreeSet<>();

    @Override
    public String filter(URL sourceUrl, Metadata sourceMetadata,
            String urlToFilter) {

        urlToFilter = urlToFilter.trim();

        if (removeAnchorPart) {
            try {
                URL theURL = new URL(urlToFilter);
                String anchor = theURL.getRef();
                if (anchor != null)
                    urlToFilter = urlToFilter.replace("#" + anchor, "");
            } catch (MalformedURLException e) {
                return null;
            }
        }

        if (unmangleQueryString) {
            urlToFilter = unmangleQueryString(urlToFilter);
        }

        if (!queryElementsToRemove.isEmpty()) {
            urlToFilter = filterQueryElements(urlToFilter);
        }

        try {
            URL theURL = new URL(urlToFilter);
            String file = theURL.getFile();
            String protocol = theURL.getProtocol();
            String host = theURL.getHost();
            boolean hasChanged = false;

            // lowercased protocol
            if (!urlToFilter.startsWith(protocol)) {
                hasChanged = true;
            }

            if (host != null) {
                String newHost = host.toLowerCase(Locale.ROOT);
                if (!host.equals(newHost)) {
                    host = newHost;
                    hasChanged = true;
                }
            }

            int port = theURL.getPort();
            // properly encode characters in path/file using percent-encoding
            String file2 = unescapePath(file);
            file2 = escapePath(file2);
            if (!file.equals(file2)) {
                hasChanged = true;
            }
            if (hasChanged) {
                urlToFilter = new URL(protocol, host, port, file2).toString();
            }
        } catch (MalformedURLException e) {
            return null;
        }

        if (checkValidURI) {
            try {
                URI uri = URI.create(urlToFilter);
                urlToFilter = uri.normalize().toString();
            } catch (java.lang.IllegalArgumentException e) {
                LOG.info("Invalid URI {}", urlToFilter);
                return null;
            }
        }

        return urlToFilter;
    }

    @Override
    public void configure(Map stormConf, JsonNode paramNode) {
        JsonNode node = paramNode.get("removeAnchorPart");
        if (node != null) {
            removeAnchorPart = node.booleanValue();
        }

        node = paramNode.get("unmangleQueryString");
        if (node != null) {
            unmangleQueryString = node.booleanValue();
        }

        node = paramNode.get("queryElementsToRemove");
        if (node != null) {
            if (!node.isArray()) {
                LOG.warn(
                        "Failed to configure queryElementsToRemove.  Not an array: {}",
                        node.toString());
            } else {
                ArrayNode array = (ArrayNode) node;
                for (JsonNode element : array) {
                    queryElementsToRemove.add(element.asText());
                }
            }
        }

        node = paramNode.get("checkValidURI");
        if (node != null) {
            checkValidURI = node.booleanValue();
        }
    }

    /**
     * Basic filter to remove query parameters from urls so parameters that
     * don't change the content of the page can be removed. An example would be
     * a google analytics query parameter like "utm_campaign" which might have
     * several different values for a url that points to the same content.
     */
    private String filterQueryElements(String urlToFilter) {
        try {
            // Handle illegal characters by making a url first
            // this will clean illegal characters like |
            URL url = new URL(urlToFilter);

            if (StringUtils.isEmpty(url.getQuery())) {
                return urlToFilter;
            }

            List<NameValuePair> pairs = new ArrayList<>();
            URLEncodedUtils.parse(pairs, new Scanner(url.getQuery()), "UTF-8");
            Iterator<NameValuePair> pairsIterator = pairs.iterator();
            while (pairsIterator.hasNext()) {
                NameValuePair param = pairsIterator.next();
                if (queryElementsToRemove.contains(param.getName())) {
                    pairsIterator.remove();
                }
            }

            StringBuilder newFile = new StringBuilder();
            if (url.getPath() != null) {
                newFile.append(url.getPath());
            }
            if (!pairs.isEmpty()) {
                Collections.sort(pairs, comp);
                String newQueryString = URLEncodedUtils.format(pairs,
                        StandardCharsets.UTF_8);
                newFile.append('?').append(newQueryString);
            }
            if (url.getRef() != null) {
                newFile.append('#').append(url.getRef());
            }

            return new URL(url.getProtocol(), url.getHost(), url.getPort(),
                    newFile.toString()).toString();
        } catch (MalformedURLException e) {
            LOG.warn("Invalid urlToFilter {}. {}", urlToFilter, e);
            return null;
        }
    }

    Comparator<NameValuePair> comp = new Comparator<NameValuePair>() {
        @Override
        public int compare(NameValuePair p1, NameValuePair p2) {
            return p1.getName().compareTo(p2.getName());
        }
    };

    /**
     * A common error to find is a query string that starts with an & instead of
     * a ? This will fix that error. So http://foo.com&a=b will be changed to
     * http://foo.com?a=b.
     * 
     * @param urlToFilter
     * @return corrected url
     */
    private String unmangleQueryString(String urlToFilter) {
        int firstAmp = urlToFilter.indexOf('&');
        if (firstAmp > 0) {
            int firstQuestionMark = urlToFilter.indexOf('?');
            if (firstQuestionMark == -1) {
                return urlToFilter.replaceFirst("&", "?");
            }
        }
        return urlToFilter;
    }

    /**
     * Remove % encoding from path segment in URL for characters which should be
     * unescaped according to <a
     * href="https://tools.ietf.org/html/rfc3986#section-2.2">RFC3986</a>.
     */
    private String unescapePath(String path) {
        StringBuilder sb = new StringBuilder();

        Matcher matcher = unescapeRulePattern.matcher(path);

        int end = -1;
        int letter;

        // Traverse over all encoded groups
        while (matcher.find()) {
            // Append everything up to this group
            sb.append(path.substring(end + 1, matcher.start()));

            // Get the integer representation of this hexadecimal encoded
            // character
            letter = Integer.valueOf(matcher.group().substring(1), 16);

            if (letter < 128 && unescapedCharacters[letter]) {
                // character should be unescaped in URLs
                sb.append(new Character((char) letter));
            } else {
                // Append the encoded character as uppercase
                sb.append(matcher.group().toUpperCase(Locale.ROOT));
            }

            end = matcher.start() + 2;
        }

        letter = path.length();

        // Append the rest if there's anything
        if (end <= letter - 1) {
            sb.append(path.substring(end + 1, letter));
        }

        // Ok!
        return sb.toString();
    }

    /**
     * Convert path segment of URL from Unicode to UTF-8 and escape all
     * characters which should be escaped according to <a
     * href="https://tools.ietf.org/html/rfc3986#section-2.2">RFC3986</a>..
     */
    private String escapePath(String path) {
        StringBuilder sb = new StringBuilder(path.length());

        // Traverse over all bytes in this URL
        for (byte b : path.getBytes(utf8)) {
            // Is this a control character?
            if (b < 33 || b == 91 || b == 93) {
                // Start escape sequence
                sb.append('%');

                // Get this byte's hexadecimal representation
                String hex = Integer.toHexString(b & 0xFF).toUpperCase(
                        Locale.ROOT);

                // Do we need to prepend a zero?
                if (hex.length() % 2 != 0) {
                    sb.append('0');
                    sb.append(hex);
                } else {
                    // No, append this hexadecimal representation
                    sb.append(hex);
                }
            } else {
                // No, just append this character as-is
                sb.append((char) b);
            }
        }

        return sb.toString();
    }
}
