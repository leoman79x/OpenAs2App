package org.openas2.partner;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openas2.OpenAS2Exception;
import org.openas2.Session;
import org.openas2.XMLSession;
import org.openas2.params.InvalidParameterException;
import org.openas2.schedule.HasSchedule;
import org.openas2.util.AS2Util;
import org.openas2.util.XMLUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A partnership factory that stores partners and partnerships in a database via JDBC.
 * Eliminates the need for a partnerships.xml file.
 *
 * <p>Configuration in config.xml:
 * <pre>
 * &lt;partnerships classname="org.openas2.partner.DbPartnershipFactory"
 *               jdbc_connect_string="jdbc:mariadb://localhost:3306/openas2"
 *               jdbc_driver="org.mariadb.jdbc.Driver"
 *               db_user="openas2"
 *               db_pwd="password"
 *               interval="120"/&gt;
 * </pre>
 *
 * <p>The JDBC driver jar must be placed in {@code <as2_base>/lib/}.
 * Database tables must be created manually using {@code config/db/partnerships_schema.sql}.
 */
public class DbPartnershipFactory extends BasePartnershipFactory
        implements HasSchedule, RefreshablePartnershipFactory, StorablePartnershipFactory {

    private static final String PARAM_JDBC_CONNECT_STRING = "jdbc_connect_string";
    private static final String PARAM_JDBC_DRIVER = "jdbc_driver";
    private static final String PARAM_DB_USER = "db_user";
    private static final String PARAM_DB_PWD = "db_pwd";
    private static final String PARAM_INTERVAL = "interval";

    private final Logger logger = LoggerFactory.getLogger(DbPartnershipFactory.class);

    private HikariDataSource dataSource;
    private Map<String, Object> partners;

    @Override
    public Map<String, Object> getPartners() {
        if (partners == null) {
            partners = new HashMap<>();
        }
        return partners;
    }

    private int getRefreshInterval() throws InvalidParameterException {
        return getParameterInt(PARAM_INTERVAL, false);
    }

    @Override
    public void init(Session session, Map<String, String> parameters) throws OpenAS2Exception {
        super.init(session, parameters);

        String jdbcDriver = getParameter(PARAM_JDBC_DRIVER, true);
        String connectString = getParameter(PARAM_JDBC_CONNECT_STRING, true);
        String dbUser = getParameter(PARAM_DB_USER, true);
        String dbPwd = getParameter(PARAM_DB_PWD, true);

        try {
            Class.forName(jdbcDriver);
        } catch (ClassNotFoundException e) {
            throw new OpenAS2Exception("JDBC driver class not found: " + jdbcDriver
                    + ". Ensure the driver jar is in <as2_base>/lib/", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connectString);
        config.setUsername(dbUser);
        config.setPassword(dbPwd);
        dataSource = new HikariDataSource(config);

        logger.info("DbPartnershipFactory initialized with JDBC URL: " + connectString);
        refresh();
    }

    @Override
    public void refresh() throws OpenAS2Exception {
        getSession().destroyPartnershipPollers(Session.PARTNERSHIP_POLLER);

        Map<String, Object> newPartners = new HashMap<>();
        List<Partnership> newPartnerships = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            loadPartnersFromDb(conn, newPartners);
            loadPartnershipsFromDb(conn, newPartners, newPartnerships);
        } catch (SQLException e) {
            throw new OpenAS2Exception("Failed to load partnerships from database: " + e.getMessage(), e);
        }

        synchronized (this) {
            this.partners = newPartners;
            setPartnerships(newPartnerships);
        }
    }

    private void loadPartnersFromDb(Connection conn, Map<String, Object> partners) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, as2_id, x509_alias, x509_alias_fallback, email FROM partners");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, String> partner = new LinkedHashMap<>();
                partner.put(Partnership.PID_NAME, rs.getString("name"));
                putIfNotNull(partner, Partnership.PID_AS2, rs.getString("as2_id"));
                putIfNotNull(partner, Partnership.PID_X509_ALIAS, rs.getString("x509_alias"));
                putIfNotNull(partner, Partnership.PID_X509_ALIAS_FALLBACK, rs.getString("x509_alias_fallback"));
                putIfNotNull(partner, Partnership.PID_EMAIL, rs.getString("email"));
                partners.put(rs.getString("name"), partner);
            }
        }
    }

    private void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private void loadPartnershipsFromDb(Connection conn, Map<String, Object> partners, List<Partnership> partnerships) throws OpenAS2Exception, SQLException {
        // Load all partnership_attributes into a map keyed by partnership_name
        Map<String, Map<String, String>> allAttributes = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT partnership_name, attr_name, attr_value FROM partnership_attributes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                allAttributes
                        .computeIfAbsent(rs.getString("partnership_name"), k -> new LinkedHashMap<>())
                        .put(rs.getString("attr_name"), rs.getString("attr_value"));
            }
        }

        // Load all poller configs into a map keyed by partnership_name
        Map<String, Map<String, String>> allPollerConfigs = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT partnership_name, attr_name, attr_value FROM partnership_poller_config");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                allPollerConfigs
                        .computeIfAbsent(rs.getString("partnership_name"), k -> new LinkedHashMap<>())
                        .put(rs.getString("attr_name"), rs.getString("attr_value"));
            }
        }

        // Load partnerships
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, sender_name, receiver_name FROM partnerships");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("name");
                String senderName = rs.getString("sender_name");
                String receiverName = rs.getString("receiver_name");

                Partnership partnership = new Partnership();
                partnership.setName(name);

                // Load sender partner IDs (same logic as XMLPartnershipFactory.loadPartnerIDs)
                @SuppressWarnings("unchecked")
                Map<String, Object> senderPartner = (Map<String, Object>) partners.get(senderName);
                if (senderPartner == null) {
                    throw new OpenAS2Exception("Partnership " + name + " has an undefined sender: " + senderName);
                }
                partnership.getSenderIDs().putAll(senderPartner);

                @SuppressWarnings("unchecked")
                Map<String, Object> receiverPartner = (Map<String, Object>) partners.get(receiverName);
                if (receiverPartner == null) {
                    throw new OpenAS2Exception("Partnership " + name + " has an undefined receiver: " + receiverName);
                }
                partnership.getReceiverIDs().putAll(receiverPartner);

                // Load attributes
                Map<String, String> attrs = allAttributes.get(name);
                if (attrs != null) {
                    AS2Util.attributeEnhancer(attrs);
                    partnership.getAttributes().putAll(attrs);
                }

                // Handle dynamic content type mapping
                if ("true".equalsIgnoreCase(partnership.getAttributeOrProperty(Partnership.PA_USE_DYNAMIC_CONTENT_TYPE_MAPPING, "false"))) {
                    try {
                        partnership.setUseDynamicContentTypeLookup(true);
                    } catch (IOException e) {
                        logger.error("Error setting up dynamic Content-Type lookup: " + e.getMessage(), e);
                        throw new OpenAS2Exception("Partnership failed to be set up correctly for dynamic Content-Type lookup: " + name);
                    }
                }

                partnerships.add(partnership);

                // Handle poller configuration
                Map<String, String> pollerConfig = allPollerConfigs.get(name);
                if (pollerConfig != null && "true".equalsIgnoreCase(pollerConfig.get("enabled"))) {
                    loadPollerFromConfig(pollerConfig, partnership);
                }
            }
        }
    }

    private void loadPollerFromConfig(Map<String, String> pollerConfig, Partnership partnership) throws OpenAS2Exception {
        if (logger.isTraceEnabled()) {
            logger.trace("Found partnership poller for partnership: " + partnership.getName());
        }

        Node basePollerConfigNode = ((XMLSession) getSession()).getBasePartnershipPollerConfig();
        if (basePollerConfigNode == null) {
            throw new OpenAS2Exception("Missing base poller config node in config.xml to configure partnership poller.");
        }

        Document pollerDoc;
        try {
            pollerDoc = XMLUtil.createDoc(basePollerConfigNode);
        } catch (Exception e) {
            throw new OpenAS2Exception("Failed to create a poller document: " + e.getMessage(), e);
        }

        Element pollerConfigElem = pollerDoc.getDocumentElement();

        // Merge the base config attributes with the partnership-specific ones
        Map<String, String> mergedAttributes = XMLUtil.mapAttributes(pollerConfigElem);
        mergedAttributes.putAll(pollerConfig);

        // Enhance attribute values for dynamic variables
        AS2Util.attributeEnhancer(mergedAttributes);

        // Update the XML element with merged values
        mergedAttributes.forEach(pollerConfigElem::setAttribute);

        // Replace $partnership.* placeholders
        replacePartnershipPlaceHolders(pollerDoc, partnership);

        // Launch a directory poller module for this config
        getSession().loadPartnershipPoller(pollerConfigElem, partnership.getName(), Session.PARTNERSHIP_POLLER);
    }

    /**
     * Replace $partnership.* placeholders in a DOM document with values from the given partnership.
     * This is the same logic as in XMLPartnershipFactory.
     */
    private void replacePartnershipPlaceHolders(Document doc, Partnership partnership) throws OpenAS2Exception {
        String xpathExpression = "//*[@*[contains(.,'$partnership.')]]/@*";
        XPathFactory xpathFactory = XPathFactory.newInstance();
        XPath xpath = xpathFactory.newXPath();
        try {
            XPathExpression expr = xpath.compile(xpathExpression);
            NodeList nodes = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
            Pattern PATTERN = Pattern.compile("\\$partnership\\.([^\\$]++)\\$");

            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                String val = node.getNodeValue();
                StringBuffer strBuf = new StringBuffer();
                Matcher matcher = PATTERN.matcher(val);
                boolean hasChanged = false;
                while (matcher.find()) {
                    String value = null;
                    String[] keys = matcher.group(1).split("\\.");
                    if (keys.length == 1) {
                        switch (keys[0]) {
                            case "name":
                                value = partnership.getName();
                                break;
                            default:
                                throw new OpenAS2Exception(
                                        "The partnership placeholder cannot be resolved: " + keys[0] + " in " + val);
                        }
                    } else if (keys.length == 2) {
                        switch (keys[0]) {
                            case "receiver":
                                value = partnership.getReceiverID(keys[1]);
                                break;
                            case "sender":
                                value = partnership.getSenderID(keys[1]);
                                break;
                            default:
                                throw new OpenAS2Exception(
                                        "The partnership placeholder cannot be resolved: " + keys[0] + " in " + val);
                        }
                    } else {
                        throw new OpenAS2Exception(
                                "The partnership placeholder is invalid and cannot be parsed: " + val);
                    }
                    if (value == null) {
                        throw new OpenAS2Exception(
                                "Missing attribute value for replacement: " + matcher.group() + " in " + val);
                    } else {
                        hasChanged = true;
                        matcher.appendReplacement(strBuf, Matcher.quoteReplacement(value));
                        if (logger.isTraceEnabled()) {
                            logger.trace("Partnership place holder replaced: " + keys + " :: Replaced with: " + value);
                        }
                    }
                }
                if (hasChanged) {
                    matcher.appendTail(strBuf);
                    node.setNodeValue(strBuf.toString());
                }
            }
        } catch (XPathExpressionException e) {
            throw new OpenAS2Exception("Error processing partnership placeholders: " + e.getMessage(), e);
        }
    }

    @Override
    public void schedule(ScheduledExecutorService executor) throws OpenAS2Exception {
        int interval = getRefreshInterval();
        if (interval > 0) {
            executor.scheduleWithFixedDelay(() -> {
                try {
                    refresh();
                    getSession().startPartnershipPollers();
                } catch (OpenAS2Exception e) {
                    logger.error("Error refreshing partnerships from database: " + e.getMessage(), e);
                }
            }, interval, interval, TimeUnit.SECONDS);
            logger.info("Scheduled partnership DB refresh every " + interval + " seconds");
        }
    }

    @Override
    public void storePartnership() throws OpenAS2Exception {
        logger.info("storePartnership() is a no-op for DbPartnershipFactory — mutations write directly to the database.");
    }

    @Override
    public void addPartner(Map<String, String> attributes) throws OpenAS2Exception {
        String name = attributes.get(Partnership.PID_NAME);
        if (name == null) {
            throw new OpenAS2Exception("Partner name is required");
        }

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO partners (name, as2_id, x509_alias, x509_alias_fallback, email) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, name);
                ps.setString(2, attributes.get(Partnership.PID_AS2));
                ps.setString(3, attributes.get(Partnership.PID_X509_ALIAS));
                ps.setString(4, attributes.get(Partnership.PID_X509_ALIAS_FALLBACK));
                ps.setString(5, attributes.get(Partnership.PID_EMAIL));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new OpenAS2Exception("Failed to add partner: " + e.getMessage(), e);
        }

        refresh();
    }

    @Override
    public void deletePartner(String name) throws OpenAS2Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM partners WHERE name = ?")) {
                ps.setString(1, name);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    throw new OpenAS2Exception("Partner not found: " + name);
                }
            }
        } catch (SQLException e) {
            throw new OpenAS2Exception("Failed to delete partner: " + e.getMessage(), e);
        }

        refresh();
    }

    @Override
    public void updatePartner(String name, Map<String, String> attributes) throws OpenAS2Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE partners SET as2_id = ?, x509_alias = ?, x509_alias_fallback = ?, email = ? WHERE name = ?")) {
                ps.setString(1, attributes.get(Partnership.PID_AS2));
                ps.setString(2, attributes.get(Partnership.PID_X509_ALIAS));
                ps.setString(3, attributes.get(Partnership.PID_X509_ALIAS_FALLBACK));
                ps.setString(4, attributes.get(Partnership.PID_EMAIL));
                ps.setString(5, name);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    throw new OpenAS2Exception("Partner not found: " + name);
                }
            }
        } catch (SQLException e) {
            throw new OpenAS2Exception("Failed to update partner: " + e.getMessage(), e);
        }

        refresh();
    }

    @Override
    public void addPartnership(String name, String senderName, String receiverName,
                               Map<String, String> attributes, Map<String, String> pollerConfig) throws OpenAS2Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert partnership row
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO partnerships (name, sender_name, receiver_name) VALUES (?, ?, ?)")) {
                    ps.setString(1, name);
                    ps.setString(2, senderName);
                    ps.setString(3, receiverName);
                    ps.executeUpdate();
                }

                // Insert attributes
                if (attributes != null && !attributes.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO partnership_attributes (partnership_name, attr_name, attr_value) VALUES (?, ?, ?)")) {
                        for (Map.Entry<String, String> entry : attributes.entrySet()) {
                            ps.setString(1, name);
                            ps.setString(2, entry.getKey());
                            ps.setString(3, entry.getValue());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                // Insert poller config
                if (pollerConfig != null && !pollerConfig.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO partnership_poller_config (partnership_name, attr_name, attr_value) VALUES (?, ?, ?)")) {
                        for (Map.Entry<String, String> entry : pollerConfig.entrySet()) {
                            ps.setString(1, name);
                            ps.setString(2, entry.getKey());
                            ps.setString(3, entry.getValue());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new OpenAS2Exception("Failed to add partnership: " + e.getMessage(), e);
        }

        refresh();
    }

    @Override
    public void deletePartnership(String name) throws OpenAS2Exception {
        try (Connection conn = dataSource.getConnection()) {
            // partnership_attributes and partnership_poller_config are ON DELETE CASCADE
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM partnerships WHERE name = ?")) {
                ps.setString(1, name);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    throw new OpenAS2Exception("Partnership not found: " + name);
                }
            }
        } catch (SQLException e) {
            throw new OpenAS2Exception("Failed to delete partnership: " + e.getMessage(), e);
        }

        refresh();
    }

    @Override
    public void updatePartnership(String name, String senderName, String receiverName,
                                  Map<String, String> attributes, Map<String, String> pollerConfig) throws OpenAS2Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Update partnership row
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE partnerships SET sender_name = ?, receiver_name = ? WHERE name = ?")) {
                    ps.setString(1, senderName);
                    ps.setString(2, receiverName);
                    ps.setString(3, name);
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        throw new OpenAS2Exception("Partnership not found: " + name);
                    }
                }

                // Replace attributes: delete all then re-insert
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM partnership_attributes WHERE partnership_name = ?")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
                if (attributes != null && !attributes.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO partnership_attributes (partnership_name, attr_name, attr_value) VALUES (?, ?, ?)")) {
                        for (Map.Entry<String, String> entry : attributes.entrySet()) {
                            ps.setString(1, name);
                            ps.setString(2, entry.getKey());
                            ps.setString(3, entry.getValue());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                // Replace poller config: delete all then re-insert
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM partnership_poller_config WHERE partnership_name = ?")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
                if (pollerConfig != null && !pollerConfig.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO partnership_poller_config (partnership_name, attr_name, attr_value) VALUES (?, ?, ?)")) {
                        for (Map.Entry<String, String> entry : pollerConfig.entrySet()) {
                            ps.setString(1, name);
                            ps.setString(2, entry.getKey());
                            ps.setString(3, entry.getValue());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new OpenAS2Exception("Failed to update partnership: " + e.getMessage(), e);
        }

        refresh();
    }

    public void destroy() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            logger.info("DbPartnershipFactory connection pool closed.");
        }
    }
}
