//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
/*
 Copyright Paul James Mutton, 2001-2004, http://www.jibble.org/

 This file is part of SimpleFTP.

 This software is dual-licensed, allowing you to choose between the GNU
 General Public License (GPL) and the www.jibble.org Commercial License.
 Since the GPL may be too restrictive for use in a proprietary application,
 a commercial license is also provided. Full license information can be
 found at http://www.jibble.org/licenses/

 $Author: pjm2 $
 $Id: SimpleFTP.java,v 1.2 2004/05/29 19:27:37 pjm2 Exp $

 */
package jd.nutils;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.rmi.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.StringTokenizer;

import org.appwork.exceptions.WTFException;
import org.appwork.utils.IO;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.logging2.extmanager.LoggerFactory;
import org.appwork.utils.net.URLHelper;
import org.appwork.utils.net.httpconnection.HTTPProxy;
import org.appwork.utils.net.httpconnection.SSLSocketStreamFactory;
import org.appwork.utils.net.httpconnection.SocketStreamInterface;
import org.jdownloader.auth.AuthenticationController;
import org.jdownloader.auth.AuthenticationInfo.Type;
import org.jdownloader.auth.Login;
import org.jdownloader.logging.LogController;
import org.jdownloader.net.BCSSLSocketStreamFactory;

/**
 * SimpleFTP is a simple package that implements a Java FTP client. With SimpleFTP, you can connect to an FTP server and upload multiple
 * files.
 *
 * Based on Work of Paul Mutton http://www.jibble.org/
 */
public abstract class SimpleFTP {
    private enum TYPE {
        FILE,
        DIR,
        LINK;
    }

    public static enum ENCODING {
        ASCII7BIT {
            @Override
            public String fromBytes(byte[] bytes) throws IOException {
                final StringBuilder sb = new StringBuilder();
                for (int index = 0; index < bytes.length; index++) {
                    final int c = bytes[index] & 0xff;
                    if (c <= 127) {
                        sb.append((char) c);
                    } else {
                        final String hexEncoded = Integer.toString(c, 16);
                        if (hexEncoded.length() == 1) {
                            sb.append("%0");
                        } else {
                            sb.append("%");
                        }
                        sb.append(hexEncoded);
                    }
                }
                return sb.toString();
            }

            private final boolean isValidHex(char c) {
                return (c >= 48 && c <= 57) || (c >= 65 && c <= 70) || (c >= 97 && c <= 102);
            }

            @Override
            public byte[] toBytes(String string) throws IOException {
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                for (int index = 0; index < string.length(); index++) {
                    final char c = string.charAt(index);
                    if (c == '%' && string.length() >= index + 2 && isValidHex(string.charAt(index + 1)) && isValidHex(string.charAt(index + 2))) {
                        final int hexDecoded = Integer.parseInt(string.substring(index + 1, index + 3), 16);
                        bos.write(hexDecoded);
                        index += 2;
                    } else {
                        bos.write(c);
                    }
                }
                return bos.toByteArray();
            }
        },
        UTF8 {
            @Override
            public String fromBytes(byte[] bytes) throws IOException {
                return URLEncoder.encode(new String(bytes, "UTF-8"), "UTF-8");
            }

            @Override
            public byte[] toBytes(String string) throws IOException {
                return URLDecoder.decode(string, "UTF-8").getBytes("UTF-8");
            }
        };
        public abstract String fromBytes(final byte[] bytes) throws IOException;

        public abstract byte[] toBytes(final String string) throws IOException;
    }

    public static enum FEATURE {
        PASV,
        SIZE,
        CLNT,
        PBSZ,
        PROT,
        MLST, // rfc3659, MLST provides data about exactly the object named on its command line, and no others.
        MLSD, // rfc3659, MLSD,on the other, lists the contents of a directory if a directory is named, otherwise a 501 reply is returned.
        PROT_C("PROT C"),
        PROT_P("PROT P"),
        AUTH_TLS("AUTH TLS"),
        // AUTH_TLS_C("AUTH TLS-C"),
        // AUTH_TLS_P("AUTH TLS-P"),
        // AUTH_SSL("AUTH SSL"),
        UTF8;
        private final String cmd;

        private FEATURE() {
            this(null);
        }

        private FEATURE(String cmd) {
            this.cmd = cmd;
        }

        public String getID() {
            if (cmd == null) {
                return name();
            } else {
                return cmd;
            }
        }

        public static FEATURE get(final String input) {
            if (input != null && input.startsWith(" ")) {
                for (FEATURE feature : values()) {
                    if (input.equalsIgnoreCase(" " + feature.getID())) {
                        return feature;
                    }
                }
            }
            return null;
        }
    }

    public static String BestEncodingGuessingURLDecode(final String urlCoded) throws IOException {
        final List<String> ret = getEncodingGuessingURIDecode(urlCoded, null);
        if (ret != null && ret.size() > 0) {
            return ret.get(0);
        } else {
            return urlCoded;
        }
    }

    // very simple and dumb guessing for the correct encoding, checks for 'Replacement Character'
    public static List<String> getEncodingGuessingURIDecode(final String urlCoded, final String tryEncoding) {
        if (StringUtils.isEmpty(urlCoded)) {
            return null;
        }
        final LinkedHashMap<String, String> results = new LinkedHashMap<String, String>();
        final List<String> encodings = new ArrayList<String>(Arrays.asList(new String[] { "UTF-8", "cp1251", "ISO-8859-5", "KOI8-R" }));
        if (tryEncoding != null) {
            encodings.add(tryEncoding);
        }
        for (final String encoding : encodings) {
            try {
                results.put(encoding, URLEncode.decodeURIComponent(urlCoded, encoding, true));
            } catch (final Throwable ignore) {
                ignore.printStackTrace();
            }
        }
        try {
            results.put("US_ASCII", new String(ENCODING.ASCII7BIT.toBytes(urlCoded), "US-ASCII"));
        } catch (final Throwable ignore) {
            ignore.printStackTrace();
        }
        final List<String> bestMatchRound1 = new ArrayList<String>();
        int best = -1;
        for (final Entry<String, String> result : results.entrySet()) {
            int count = 0;
            final String value = result.getValue();
            for (int index = 0; index < value.length(); index++) {
                if ('\uFFFD' == value.charAt(index)) {
                    count++;
                }
            }
            if (best == -1 || best == count) {
                best = count;
                bestMatchRound1.add(result.getKey());
            } else if (count < best) {
                best = count;
                bestMatchRound1.clear();
                bestMatchRound1.add(result.getKey());
            }
        }
        final List<String> bestMatches = new ArrayList<String>();
        for (final String bestMatchEncoding : bestMatchRound1) {
            final String result = results.get(bestMatchEncoding);
            if (!bestMatches.contains(result)) {
                bestMatches.add(result);
            }
        }
        if (bestMatches.size() > 0) {
            Collections.sort(bestMatches, new Comparator<String>() {
                private final int compare(int x, int y) {
                    return (x < y) ? -1 : ((x == y) ? 0 : 1);
                }

                @Override
                public final int compare(String o1, String o2) {
                    return compare(o1.length(), o2.length());
                }
            });
        }
        if (bestMatches.size() > 0) {
            return bestMatches;
        } else {
            return null;
        }
    }

    private boolean               binarymode         = false;
    private SocketStreamInterface socket             = null;
    private String                dir                = "/";
    private String                host;
    private final LogInterface    logger;
    private String                latestResponseLine = null;
    private String                user               = null;
    private final byte[]          CRLF               = "\r\n".getBytes();

    public String getUser() {
        return user;
    }

    public int getReadTimeout(STATE state) {
        switch (state) {
        case CLOSING:
            return 10 * 1000;
        case CONNECTING:
        case CONNECTED:
        case DOWNLOADING:
        default:
            return 30 * 1000;
        }
    }

    public String getPass() {
        return pass;
    }

    private String pass = null;

    public LogInterface getLogger() {
        return logger;
    }

    private final HTTPProxy proxy;
    private int             port = -1;

    public int getPort() {
        return port;
    }

    public HTTPProxy getProxy() {
        return proxy;
    }

    protected abstract Socket createSocket();

    public SimpleFTP(HTTPProxy proxy, LogInterface logger) {
        this.proxy = proxy;
        if (logger != null) {
            this.logger = logger;
        } else {
            this.logger = LoggerFactory.getDefaultLogger();
        }
    }

    /**
     * Enter ASCII mode for sending text files. This is usually the default mode. Make sure you use binary mode if you are sending images or
     * other binary data, as ASCII mode is likely to corrupt them.
     */
    public boolean ascii() throws IOException {
        sendLine("TYPE A");
        try {
            readLines(new int[] { 200 }, "could not enter ascii mode");
            if (binarymode) {
                binarymode = false;
            }
            return true;
        } catch (IOException e) {
            LogController.CL().log(e);
            if (e.getMessage().contains("ascii")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Enter binary mode for sending binary files.
     */
    public boolean bin() throws IOException {
        sendLine("TYPE I");
        try {
            readLines(new int[] { 200 }, "could not enter binary mode");
            if (!binarymode) {
                binarymode = true;
            }
            return true;
        } catch (IOException e) {
            LogController.CL().log(e);
            if (e.getMessage().contains("binary")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * returns current value of 'binarymode'.
     *
     * @since JD2
     */
    public boolean isBinary() {
        return binarymode;
    }

    /**
     * Connects to the default port of an FTP server and logs in as anonymous/anonymous.
     */
    public void connect(String host) throws IOException {
        connect(host, 21);
    }

    /**
     * Connects to an FTP server and logs in as anonymous/anonymous.
     */
    public void connect(String host, int port) throws IOException {
        connect(host, port, "anonymous", "anonymous");
    }

    private String[] getLines(String lines) {
        final String[] ret = Regex.getLines(lines);
        if (ret.length == 0) {
            return new String[] { lines.trim() };
        } else {
            return ret;
        }
    }

    public SocketStreamInterface createSocket(InetSocketAddress address) throws IOException {
        final Socket socket = createSocket();
        try {
            socket.connect(address, getConnectTimeout());
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        final SocketStreamInterface ret = new SocketStreamInterface() {
            @Override
            public Socket getSocket() {
                return socket;
            }

            @Override
            public OutputStream getOutputStream() throws IOException {
                return socket.getOutputStream();
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return socket.getInputStream();
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        };
        switch (getTLSMode()) {
        case EXPLICIT_OPTIONAL_CC_DC:
        case EXPLICIT_REQUIRED_CC_DC:
            try {
                // TODO: add SSLSocketStream options support, caching + retry + trustAll
                return getSSLSocketStreamFactory().create(ret, address.getAddress().getHostAddress(), address.getPort(), true, null);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        default:
            return ret;
        }
    }

    public int getConnectTimeout() {
        return 60 * 1000;
    }

    public static enum STATE {
        CONNECTING,
        CONNECTED,
        DOWNLOADING,
        CLOSING
    }

    public static enum TLS_MODE {
        NONE,
        EXPLICIT_OPTIONAL_CC,
        EXPLICIT_OPTIONAL_CC_DC,
        EXPLICIT_REQUIRED_CC,
        EXPLICIT_REQUIRED_CC_DC,
    }

    private TLS_MODE tlsMode = TLS_MODE.NONE;

    protected void setTLSMode(final TLS_MODE mode) {
        tlsMode = mode;
    }

    public TLS_MODE getTLSMode() {
        return tlsMode;
    }

    public void connect(String host, int port, String user, String pass) throws IOException {
        final TLS_MODE tlsMode = getPreferedTLSMode();
        try {
            connect(host, port, user, pass, tlsMode);
        } catch (IOException e) {
            if (StringUtils.containsIgnoreCase(e.getMessage(), "plaintext") && (TLS_MODE.EXPLICIT_OPTIONAL_CC.equals(tlsMode) || TLS_MODE.EXPLICIT_OPTIONAL_CC_DC.equals(tlsMode))) {
                logger.log(e);
                try {
                    disconnect();
                } catch (IOException ignore) {
                }
                setPreferedTLSMode(TLS_MODE.NONE);
                connect(host, port, user, pass, TLS_MODE.NONE);
            } else {
                throw e;
            }
        }
    }

    public boolean isWrongLoginException(IOException e) {
        return StringUtils.containsIgnoreCase(e.getMessage(), "530 Login or Password incorrect");
    }

    public Integer getConnectionLimitByException(IOException e) {
        final String msg = e.getMessage();
        if ((StringUtils.containsIgnoreCase(msg, "530 No more connection allowed"))) {
            return -1;
        } else if ((StringUtils.containsIgnoreCase(msg, "530 Stop connecting"))) {
            final String maxConnections = new Regex(e.getMessage(), "You have\\s*(\\d+)\\s*connections now currently opened").getMatch(0);
            if (maxConnections != null) {
                return Math.max(1, Integer.parseInt(maxConnections) - 1);
            } else {
                return -1;
            }
        } else if (StringUtils.containsIgnoreCase(msg, "Sorry, the maximum number of clients") || StringUtils.startsWithCaseInsensitive(msg, "421")) {
            final String maxConnections = new Regex(e.getMessage(), "Sorry, the maximum number of clients \\((\\d+)\\)").getMatch(0);
            if (maxConnections != null) {
                return Math.max(1, Integer.parseInt(maxConnections) - 1);
            } else {
                return -1;
            }
        } else {
            return null;
        }
    }

    /**
     * Connects to an FTP server and logs in with the supplied username and password.
     */
    protected void connect(String host, int port, String user, String pass, TLS_MODE mode) throws IOException {
        if (getControlSocket() != null) {
            throw new IOException("SimpleFTP is already connected. Disconnect first.");
        }
        setTLSMode(TLS_MODE.NONE);
        this.isUTF8Enabled = false;
        this.user = user;
        this.pass = pass;
        socket = createSocket(new InetSocketAddress(host, port));
        this.host = host;
        this.port = port;
        socket.getSocket().setSoTimeout(getReadTimeout(STATE.CONNECTING));
        String response = readLines(new int[] { 220 }, "SimpleFTP received an unknown response when connecting to the FTP server: ");
        socket.getSocket().setSoTimeout(getReadTimeout(STATE.CONNECTED));
        switch (mode) {
        case EXPLICIT_REQUIRED_CC:
        case EXPLICIT_OPTIONAL_CC:
            if (AUTH_TLS_CC()) {
                setTLSMode(mode);
            } else if (TLS_MODE.EXPLICIT_REQUIRED_CC.equals(mode)) {
                throw new IOException("TLS_MODE:" + mode + " failed!");
            }
            break;
        case EXPLICIT_REQUIRED_CC_DC:
        case EXPLICIT_OPTIONAL_CC_DC:
            final boolean ccTLS = AUTH_TLS_CC();
            if (ccTLS && AUTH_TLS_DC()) {
                setTLSMode(mode);
            } else if (TLS_MODE.EXPLICIT_REQUIRED_CC_DC.equals(mode)) {
                throw new IOException("TLS_MODE:" + mode + " failed!");
            } else if (ccTLS) {
                setTLSMode(TLS_MODE.EXPLICIT_OPTIONAL_CC);
            } else {
                setTLSMode(TLS_MODE.NONE);
            }
            break;
        default:
            setTLSMode(TLS_MODE.NONE);
            break;
        }
        sendLine("USER " + user);
        response = readLines(new int[] { 230, 331 }, "SimpleFTP received an unknown response after sending the user: ");
        String[] lines = getLines(response);
        if (lines[lines.length - 1].startsWith("331")) {
            sendLine("PASS " + pass);
            response = readLines(new int[] { 230 }, "SimpleFTP was unable to log in with the supplied password: ");
        }
        sendLine("PWD");
        while ((response = readLine()).startsWith("230") || response.charAt(0) >= '9' || response.charAt(0) <= '0') {
        }
        //
        if (!response.startsWith("257 ")) {
            throw new IOException("PWD COmmand not understood " + response);
        }
        // Response: 257 "/" is the current directory
        dir = new Regex(response, "\"(.*)\"").getMatch(0);
        // dir = dir;
        // Now logged in.
        if (TLS_MODE.NONE.equals(getTLSMode())) {
            switch (mode) {
            case EXPLICIT_OPTIONAL_CC:
                if (AUTH_TLS_CC()) {
                    setTLSMode(mode);
                }
                break;
            case EXPLICIT_OPTIONAL_CC_DC:
                final boolean ccTLS = AUTH_TLS_CC();
                if (ccTLS && AUTH_TLS_DC()) {
                    setTLSMode(mode);
                } else if (ccTLS) {
                    setTLSMode(TLS_MODE.EXPLICIT_OPTIONAL_CC);
                }
                break;
            default:
                break;
            }
        }
    }

    private ENCODING getPathEncoding() {
        if (isUTF8Enabled) {
            return ENCODING.UTF8;
        } else {
            return ENCODING.ASCII7BIT;
        }
    }

    /**
     * Changes the working directory (like cd). Returns true if successful.RELATIVE!!!
     */
    public boolean cwd(String dir) throws IOException {
        dir = dir.replaceAll("[\\\\|//]+?", "/");
        if (dir.equals(this.dir)) {
            return true;
        }
        final ENCODING encoding = ENCODING.ASCII7BIT;
        sendLine(encoding, "CWD " + dir);
        try {
            readLines(encoding, new int[] { 250 }, "SimpleFTP was unable to change directory");
            if (!dir.endsWith("/") && !dir.endsWith("\\")) {
                dir += "/";
            }
            if (dir.startsWith("/")) {
                this.dir = dir;
            } else {
                this.dir += dir;
            }
            return true;
        } catch (IOException e) {
            LogController.CL().log(e);
            if (e.getMessage().contains("was unable to change")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Disconnects from the FTP server.
     */
    public void disconnect() throws IOException {
        final SocketStreamInterface lsocket = getControlSocket();
        try {
            /* avoid stackoverflow for io-exception during sendLine */
            socket = null;
            if (lsocket != null) {
                sendLine(ENCODING.ASCII7BIT, lsocket, "QUIT");
            }
        } finally {
            try {
                if (lsocket != null) {
                    lsocket.close();
                }
            } catch (final Throwable e) {
            }
        }
    }

    /**
     * Returns the working directory of the FTP server it is connected to.
     */
    public String pwd() throws IOException {
        sendLine("PWD");
        String dir = null;
        String response = readLines(new int[] { 257 }, null);
        if (response.startsWith("257 ")) {
            int firstQuote = response.indexOf('\"');
            int secondQuote = response.indexOf('\"', firstQuote + 1);
            if (secondQuote > 0) {
                dir = response.substring(firstQuote + 1, secondQuote);
            }
        }
        return dir;
    }

    public String readLine(ENCODING encoding) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final int length = readLine(getControlSocket().getInputStream(), bos);
        if (length == -1) {
            throw new EOFException();
        } else if (length == 0) {
            return null;
        } else {
            final String line = encoding.fromBytes(bos.toByteArray());
            logger.info(host + " < " + line);
            return line;
        }
    }

    public String readLine() throws IOException {
        return readLine(ENCODING.ASCII7BIT);
    }

    protected int readLine(InputStream is, final OutputStream buffer) throws IOException {
        int c = 0;
        int length = 0;
        boolean CR = false;
        while (true) {
            c = is.read();
            if (c == -1) {
                if (length > 0) {
                    return length;
                }
                return -1;
            } else if (c == 13) {
                if (CR) {
                    throw new IOException("CRCR!?");
                } else {
                    CR = true;
                }
            } else if (c == 10) {
                if (CR) {
                    break;
                } else {
                    throw new IOException("LF!?");
                }
            } else {
                if (CR) {
                    throw new IOException("CRXX!?");
                }
                buffer.write(c);
                length++;
            }
        }
        return length;
    }

    public boolean wasLatestOperationNotPermitted() {
        final String latest = getLastestResponseLine();
        if (latest != null) {
            return StringUtils.containsIgnoreCase(latest, "No permission") || StringUtils.containsIgnoreCase(latest, "operation not permitted") || StringUtils.containsIgnoreCase(latest, "Access is denied");
        }
        return false;
    }

    public String getLastestResponseLine() {
        return latestResponseLine;
    }

    // RFC 2389
    public List<FEATURE> listFeatures() throws IOException {
        sendLine("FEAT");
        final List<FEATURE> ret = new ArrayList<FEATURE>();
        final String response = readLines(new int[] { 211, 500, 502 }, "FEAT FAILED");
        if (StringUtils.startsWithCaseInsensitive(response, "211")) {
            final String[] featureLines = response.split("\r\n");
            for (final String featureLine : featureLines) {
                final String featureParams[] = new Regex(featureLine, "^ (\\w+)\\s+(.+);$").getRow(0);
                final List<String> features = new ArrayList<String>();
                if (featureParams != null) {
                    final String[] params = featureParams[1].split(";");
                    for (final String param : params) {
                        features.add(" " + featureParams[0] + " " + param);
                    }
                } else {
                    features.add(featureLine);
                }
                for (final String feature : features) {
                    final FEATURE knownFeature = FEATURE.get(feature);
                    if (knownFeature != null && !ret.contains(knownFeature)) {
                        ret.add(knownFeature);
                    }
                }
            }
        }
        return ret;
    }

    private static SSLSocketStreamFactory defaultSSLSocketStreamFactory = null;

    public static void setDefaultSSLSocketStreamFactory(SSLSocketStreamFactory defaultSSLSocketStreamFactory) {
        SimpleFTP.defaultSSLSocketStreamFactory = defaultSSLSocketStreamFactory;
    }

    public static SSLSocketStreamFactory getDefaultSSLSocketStreamFactory() {
        final SSLSocketStreamFactory ret = defaultSSLSocketStreamFactory;
        if (ret != null) {
            return ret;
        } else {
            return new BCSSLSocketStreamFactory();
        }
    }

    protected boolean sslTrustALL = true;

    public void setSSLTrustALL(boolean trustALL) {
        this.sslTrustALL = trustALL;
    }

    public boolean isSSLTrustALL() {
        return this.sslTrustALL;
    }

    protected SSLSocketStreamFactory getSSLSocketStreamFactory() {
        return getDefaultSSLSocketStreamFactory();
    }

    public static enum RESPONSE_CODE {
        // 234 AUTH command OK. Initializing SSL connection.
        OK_234,
        // 334 [ADAT=base64data]
        // https://tools.ietf.org/html/rfc2228
        // code is sent in response to the AUTH command when the requested security mechanism is accepted and includes a security data to be
        // used by the client to construct the next command. The square brackets are not to be included in the response and it is optional
        // to indicate the security data in the response.
        OK_334,
        // Service not available, closing control connection.
        FAILED_421,
        // Need unavailable resource to process security.
        FAILED_431,
        // Syntax error in parameters or argument.
        FAILED_501,
        // Syntax error, command unrecognized.
        FAILED_500,
        // Command not implemented
        FAILED_502,
        // 504 Command not implemented for that parameter.
        FAILED_504,
        // 530 Not logged in.
        FAILED_530,
        // Request denied for policy reasons.
        // command is disabled.
        FAILED_534;
        public int code() {
            return Integer.parseInt(name().substring(name().indexOf("_") + 1));
        }
    }

    // RFC 4217
    protected boolean AUTH_TLS_CC() throws IOException {
        sendLine("AUTH TLS");
        final String response = readLines(new int[] { RESPONSE_CODE.OK_234.code(), RESPONSE_CODE.FAILED_500.code(), RESPONSE_CODE.FAILED_502.code(), RESPONSE_CODE.FAILED_504.code(), RESPONSE_CODE.FAILED_530.code(), RESPONSE_CODE.FAILED_534.code() }, "AUTH_TLS FAILED");
        if (StringUtils.startsWithCaseInsensitive(response, "234")) {
            // TODO: add SSLSocketStream options support, caching + retry + trustAll
            socket = getSSLSocketStreamFactory().create(getControlSocket(), "", getPort(), true, null);
            return true;
        } else {
            return false;
        }
    }

    // RFC 4217
    protected boolean AUTH_TLS_DC() throws IOException {
        return PBSZ(0) && PROT(true);
    }

    protected boolean PBSZ(final int size) throws IOException {
        sendLine("PBSZ " + size);
        final String response = readLines(new int[] { 200, 500, 502 }, "PBSZ " + size + " failed");
        return StringUtils.startsWithCaseInsensitive(response, "200");
    }

    protected boolean PROT(final boolean privateFlag) throws IOException {
        sendLine(privateFlag ? "PROT P" : "PROT C");
        final String response = readLines(new int[] { 200, 500, 502 }, privateFlag ? "PROT P" : "PROT C");
        return StringUtils.startsWithCaseInsensitive(response, "200");
    }

    public boolean sendClientID(final String id) throws IOException {
        sendLine("CLNT " + id);
        final String response = readLines(new int[] { 200, 500, 502 }, "CNLT failed");
        return StringUtils.startsWithCaseInsensitive(response, "200");
    }

    private boolean isUTF8Enabled = false;

    public boolean isUTF8() {
        return isUTF8Enabled;
    }

    // https://tools.ietf.org/html/draft-ietf-ftpext-utf-8-option-00
    public boolean setUTF8(final boolean on) throws IOException {
        if (on) {
            sendLine("OPTS UTF8 ON");
        } else {
            sendLine("OPTS UTF8 OFF");
        }
        final String response = readLines(new int[] { 200, 500, 501, 502 }, "UTF8 not supported");
        final boolean ret = StringUtils.startsWithCaseInsensitive(response, "200");
        isUTF8Enabled = ret && on;
        return ret;
    }

    public String readLines(int expectcodes[], String errormsg) throws IOException {
        return readLines(ENCODING.ASCII7BIT, expectcodes, errormsg);
    }

    /* read response and check if it matches expectcode */
    public String readLines(ENCODING encoding, int expectcodes[], String errormsg) throws IOException {
        StringBuilder sb = new StringBuilder();
        String response = null;
        boolean multilineResponse = false;
        boolean error = true;
        int endCodeMultiLine = 0;
        while (true) {
            response = readLine(encoding);
            latestResponseLine = response;
            if (response == null) {
                if (sb.length() == 0) {
                    throw new EOFException("no response received, EOF?");
                } else {
                    return sb.toString();
                }
            }
            sb.append(response + "\r\n");
            error = true;
            for (int expectcode : expectcodes) {
                if (response.startsWith("" + expectcode + "-")) {
                    /* multiline response, RFC 640 */
                    endCodeMultiLine = expectcode;
                    error = false;
                    multilineResponse = true;
                    break;
                }
                if (multilineResponse == true && response.startsWith("" + endCodeMultiLine + " ")) {
                    /* end of response of multiline */
                    return sb.toString();
                }
                if (multilineResponse == false && response.startsWith("" + expectcode + " ")) {
                    /* end of response */
                    return sb.toString();
                }
                if (response.startsWith("" + expectcode)) {
                    error = false;
                    break;
                }
            }
            if (error && !multilineResponse) {
                throw new IOException((errormsg != null ? errormsg : "revieved unexpected responsecode ") + sb.toString());
            }
        }
    }

    public long getSize(final String filePath) throws IOException {
        sendLine(getPathEncoding(), "SIZE " + filePath);
        String size = null;
        try {
            size = readLines(new int[] { 200, 213 }, "SIZE failed");
        } catch (IOException e) {
            if (e.getMessage().contains("SIZE") || e.getMessage().contains("550")) {
                logger.log(e);
                return -1;
            } else {
                throw e;
            }
        }
        final String[] split = size.split(" ");
        return Long.parseLong(split[1].trim());
    }

    /**
     * Sends a raw command to the FTP server.
     */
    public void sendLine(ENCODING encoding, String line) throws IOException {
        sendLine(encoding, this.getControlSocket(), line);
    }

    public void sendLine(String line) throws IOException {
        sendLine(ENCODING.ASCII7BIT, this.getControlSocket(), line);
    }

    private void sendLine(ENCODING encoding, SocketStreamInterface socket, String line) throws IOException {
        if (socket != null) {
            try {
                logger.info(host + " > " + line);
                final OutputStream os = socket.getOutputStream();
                // some server are buggy when CRLF is send in extra TCP packet.(maybe firewall?)
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bos.write(encoding.toBytes(line));
                bos.write(CRLF);
                bos.writeTo(os);
                os.flush();
            } catch (IOException e) {
                logger.log(e);
                if (socket != null) {
                    disconnect();
                }
                throw e;
            }
        }
    }

    public void cancelTransfer() {
        try {
            this.sendLine("ABOR");
            readLine();
        } catch (IOException e) {
            logger.log(e);
        }
    }

    public InetSocketAddress pasv() throws IOException {
        sendLine("PASV");
        String response = readLines(new int[] { 227 }, "SimpleFTP could not request passive mode:");
        String ip = null;
        int port = -1;
        int opening = response.indexOf('(');
        int closing = response.indexOf(')', opening + 1);
        if (closing > 0) {
            String dataLink = response.substring(opening + 1, closing);
            StringTokenizer tokenizer = new StringTokenizer(dataLink, ",");
            try {
                ip = tokenizer.nextToken() + "." + tokenizer.nextToken() + "." + tokenizer.nextToken() + "." + tokenizer.nextToken();
                port = Integer.parseInt(tokenizer.nextToken()) * 256 + Integer.parseInt(tokenizer.nextToken());
                return new InetSocketAddress(ip, port);
            } catch (Exception e) {
                throw new IOException("SimpleFTP received bad data link information: " + response, e);
            }
        }
        throw new IOException("SimpleFTP received bad data link information: " + response);
    }

    public String getDir() {
        return dir;
    }

    public void download(String filename, File file, boolean restart) throws IOException {
        long resumePosition = 0;
        if (!binarymode) {
            logger.info("Warning: Download in ASCII mode may fail!");
        }
        InetSocketAddress pasv = pasv();
        if (restart) {
            resumePosition = file.length();
            if (resumePosition > 0) {
                sendLine("REST " + resumePosition);
                readLines(new int[] { 350 }, "Resume not supported");
            }
        }
        InputStream input = null;
        RandomAccessFile fos = null;
        SocketStreamInterface dataSocket = null;
        try {
            final long resumeAmount = resumePosition;
            dataSocket = createSocket(new InetSocketAddress(pasv.getHostName(), pasv.getPort()));
            dataSocket.getSocket().setSoTimeout(getReadTimeout(STATE.DOWNLOADING));
            sendLine("RETR " + filename);
            input = dataSocket.getInputStream();
            fos = new RandomAccessFile(file, "rw");
            if (resumePosition > 0) {
                /* in case we do resume, reposition the writepointer */
                fos.seek(resumePosition);
            }
            String response = readLines(new int[] { 150, 125 }, null);
            byte[] buffer = new byte[32767];
            int bytesRead = 0;
            long counter = resumePosition;
            while ((bytesRead = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    /* max 10 seks wait for buggy servers */
                    getControlSocket().getSocket().setSoTimeout(getReadTimeout(STATE.CLOSING));
                    shutDownSocket(dataSocket);
                    input.close();
                    try {
                        response = readLine();
                    } catch (SocketTimeoutException e) {
                        logger.log(e);
                        response = "SocketTimeout because of buggy Server";
                    }
                    this.shutDownSocket(dataSocket);
                    input.close();
                    throw new InterruptedIOException();
                }
                counter += bytesRead;
                if (bytesRead > 0) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            /* max 10 seks wait for buggy servers */
            getControlSocket().getSocket().setSoTimeout(getReadTimeout(STATE.CLOSING));
            shutDownSocket(dataSocket);
            input.close();
            try {
                response = readLine();
            } catch (SocketTimeoutException e) {
                logger.log(e);
                response = "SocketTimeout because of buggy Server";
            }
            if (!response.startsWith("226")) {
                throw new IOException("Download failed: " + response);
            }
        } catch (SocketTimeoutException e) {
            logger.log(e);
            sendLine("ABOR");
            readLine();
            download(filename, file);
            return;
        } catch (SocketException e) {
            logger.log(e);
            sendLine("ABOR");
            readLine();
            download(filename, file);
            return;
        } catch (ConnectException e) {
            logger.log(e);
            sendLine("ABOR");
            readLine();
            download(filename, file);
            return;
        } finally {
            try {
                input.close();
            } catch (Throwable e) {
            }
            try {
                fos.close();
            } catch (Throwable e) {
            }
            shutDownSocket(dataSocket);
        }
    }

    public SocketStreamInterface getControlSocket() {
        return socket;
    }

    public void download(String filename, File file) throws IOException {
        download(filename, file, false);
    }

    protected String getURL(final String path) {
        final String auth;
        if (!StringUtils.equals("anonymous", getUser()) || !StringUtils.equals("anonymous", getUser())) {
            auth = getUser() + ":" + getPass() + "@";
        } else {
            auth = "";
        }
        if (StringUtils.isEmpty(path)) {
            return "ftp://" + auth + host + ":" + port;
        } else if (path.startsWith("/")) {
            return "ftp://" + auth + host + ":" + port + path;
        } else {
            return "ftp://" + auth + host + ":" + port + "/" + path;
        }
    }

    public void shutDownSocket(SocketStreamInterface dataSocket) {
        if (dataSocket != null) {
            try {
                final Socket socket = dataSocket.getSocket();
                try {
                    socket.shutdownOutput();
                } catch (Throwable e) {
                }
                try {
                    socket.shutdownInput();
                } catch (Throwable e) {
                }
            } finally {
                try {
                    dataSocket.close();
                } catch (Throwable e) {
                }
            }
        }
    }

    public class SimpleFTPListEntry {
        public final boolean isFile() {
            return TYPE.FILE.equals(getType());
        }

        public final boolean isDir() {
            return TYPE.DIR.equals(getType());
        }

        public final boolean isLink() {
            return TYPE.LINK.equals(getType());
        }

        private final TYPE getType() {
            return type;
        }

        public final String getName() {
            return name;
        }

        public final String getDest() {
            if (isLink()) {
                return getCwd() + dest;
            } else {
                return null;
            }
        }

        public final long getSize() {
            switch (getType()) {
            case FILE:
                return size;
            case DIR:
                return 0;
            default:
            case LINK:
                return -1;
            }
        }

        private final String name;
        private final String dest;
        private final long   size;
        private final TYPE   type;
        private final String cwd;

        private SimpleFTPListEntry(boolean isFile, String name, String cwd, long size) {
            this.type = isFile ? TYPE.FILE : TYPE.DIR;
            this.name = name;
            this.size = size;
            this.cwd = cwd;
            this.dest = null;
        }

        private SimpleFTPListEntry(String name, String dest, String cwd) {
            this.type = TYPE.LINK;
            this.name = name;
            this.dest = dest;
            this.size = -1;
            this.cwd = cwd;
        }

        public final URL getURL() throws IOException {
            return URLHelper.fixPathTraversal(new URL(SimpleFTP.this.getURL(getFullPath())));
        }

        public final String getCwd() {
            return cwd;
        }

        public final String getFullPath() {
            if (getCwd().endsWith("/")) {
                return getCwd() + getName();
            } else {
                return getCwd() + "/" + getName();
            }
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            switch (getType()) {
            case FILE:
                sb.append("File:");
                break;
            case DIR:
                sb.append("Dir:");
                break;
            case LINK:
                sb.append("Link:");
                break;
            }
            sb.append(getFullPath());
            if (isLink()) {
                sb.append(" -> ");
                sb.append(getDest());
            }
            if (isFile()) {
                sb.append("|Size:").append(getSize());
            }
            if (true) {
                try {
                    sb.append("|URL:" + getURL().toString());
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
            return sb.toString();
        }
    }

    public SimpleFTPListEntry[] listEntries() throws IOException {
        return listEntries_LIST();
    }

    protected SimpleFTPListEntry[] listEntries_LIST() throws IOException {
        final String[][] entries = LIST();
        if (entries != null) {
            // convert spaces to %20 like browser does
            final String cwd = getDir().replaceAll(" ", "%20");
            final List<SimpleFTPListEntry> ret = new ArrayList<SimpleFTPListEntry>();
            for (final String[] entry : entries) {
                if (entry.length == 4) {
                    final boolean isFile = !"<DIR>".equalsIgnoreCase(entry[2]);
                    final String name = entry[3];
                    final long size = isFile ? Long.parseLong(entry[2]) : -1;
                    ret.add(new SimpleFTPListEntry(isFile, name.replaceAll(" ", "%20"), cwd, size));
                } else if (entry.length == 7) {
                    final boolean isFolder = entry[0].startsWith("d");
                    final String name = entry[6];
                    final boolean isLink;
                    if (name.contains(" -> ")) {
                        // symlink
                        isLink = true;
                    } else {
                        isLink = entry[0].startsWith("l");
                    }
                    final boolean isFile = !isFolder || entry[0].startsWith("-");
                    final long size = isFile ? Long.parseLong(entry[4]) : -1;
                    if (isLink) {
                        final String link[] = new Regex(name, "^(.*?)\\s*->\\s*(.+)$").getRow(0);
                        ret.add(new SimpleFTPListEntry(link[0].replaceAll(" ", "%20"), link[1].replaceAll(" ", "%20"), cwd));
                    } else {
                        ret.add(new SimpleFTPListEntry(isFile, name.replaceAll(" ", "%20"), cwd, size));
                    }
                }
            }
            return ret.toArray(new SimpleFTPListEntry[0]);
        }
        return null;
    }

    /**
     * returns permissionmask, ?, user?, group?, size?, date, name
     *
     * @return
     * @throws IOException
     */
    protected String[][] LIST() throws IOException {
        InetSocketAddress pasv = pasv();
        sendLine("LIST");
        SocketStreamInterface dataSocket = null;
        final StringBuilder sb = new StringBuilder();
        try {
            dataSocket = createSocket(new InetSocketAddress(pasv.getHostName(), pasv.getPort()));
            readLines(new int[] { 125, 150 }, null);
            final ENCODING encoding = getPathEncoding();
            sb.append(encoding.fromBytes(IO.readStream(-1, dataSocket.getInputStream(), new ByteArrayOutputStream(), false)));
        } catch (IOException e) {
            if (e.getMessage().contains("550")) {
                logger.log(e);
                return null;
            } else {
                throw e;
            }
        } finally {
            shutDownSocket(dataSocket);
        }
        readLines(new int[] { 226 }, null);
        /* permission,type,user,group,size,date,filename */
        final String listResponse = sb.toString();
        String[][] matches = new Regex(listResponse, "([-dxrw]+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\S+\\s+\\S+\\s+\\S+)\\s+(.*?)[$\r\n]+").getMatches();
        if (matches == null || matches.length == 0) {
            /* date,time,size,name */
            matches = new Regex(listResponse, "(\\S+)\\s+(\\S+)\\s+(<DIR>|\\d+)\\s+(.*?)[$\r\n]+").getMatches();
        }
        return matches;
    }

    protected SimpleFTPListEntry getFileInfo_LIST(final String path) throws IOException {
        final String name = path.substring(path.lastIndexOf("/") + 1);
        final String workingDir = path.substring(0, path.lastIndexOf("/"));
        if (!this.cwd(workingDir)) {
            return null;
        }
        final ENCODING encoding = getPathEncoding();
        final byte[] nameBytes = encoding.toBytes(name);
        for (final SimpleFTPListEntry entry : listEntries_LIST()) {
            // we compare bytes because of hex encoding
            if (Arrays.equals(encoding.toBytes(entry.getName()), nameBytes)) {
                return entry;
            }
        }
        return null;
    }

    public SimpleFTPListEntry getFileInfo(final String path) throws IOException {
        return getFileInfo_LIST(path);
    }

    protected TLS_MODE preferedTLSMode = TLS_MODE.EXPLICIT_OPTIONAL_CC_DC;

    protected void setPreferedTLSMode(TLS_MODE mode) {
        if (mode == null) {
            preferedTLSMode = TLS_MODE.NONE;
        } else {
            preferedTLSMode = mode;
        }
    }

    public TLS_MODE getPreferedTLSMode() {
        return preferedTLSMode;
    }

    protected List<Login> getLogins(URL url) {
        final List<Login> logins = new ArrayList<Login>();
        if (url.getUserInfo() != null) {
            final String[] auth = url.getUserInfo().split(":");
            final String username = auth.length > 0 ? auth[0] : null;
            final String password = auth.length == 2 ? auth[1] : null;
            logins.add(new Login(Type.FTP, url.getHost(), null, username, password, true));
        }
        logins.addAll(AuthenticationController.getInstance().getSortedLoginsList(url, null));
        if (isAnonymousLoginSupported(url)) {
            logins.add(new Login(Type.FTP, url.getHost(), null, "anonymous", "anonymous", false));
        }
        return logins;
    }

    protected boolean isAnonymousLoginSupported(URL url) {
        return true;
    }

    /**
     * COnnect to the url.does not change directory
     *
     * @param url
     * @throws IOException
     */
    public Login connect(final URL url) throws IOException {
        final String host = url.getHost();
        int port = url.getPort();
        if (port <= 0) {
            port = url.getDefaultPort();
        }
        final List<Login> logins = getLogins(url);
        final Iterator<Login> it = logins.iterator();
        while (it.hasNext()) {
            final Login login = it.next();
            try {
                connect(host, port, login.getUsername(), login.getPassword());
                login.validate();
                return login;
            } catch (IOException e) {
                disconnect();
                if (it.hasNext() && isWrongLoginException(e)) {
                    logger.log(e);
                    continue;
                } else {
                    throw e;
                }
            }
        }
        throw new WTFException();
    }

    public static byte[] toRawBytes(String nameString) throws IOException {
        return ENCODING.ASCII7BIT.toBytes(nameString);
    }
}
