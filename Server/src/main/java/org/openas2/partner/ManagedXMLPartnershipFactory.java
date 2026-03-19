package org.openas2.partner;

import org.openas2.OpenAS2Exception;
import org.openas2.WrappedException;
import org.openas2.util.XMLUtil;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;

/**
 * A partnership factory that serializes from in-memory state (partners map + partnerships list)
 * instead of the DOM, guaranteeing correct element ordering and clean formatting.
 *
 * <p>This avoids two issues with the default {@link XMLPartnershipFactory}:
 * <ul>
 *   <li>Whitespace bloat — every load/save cycle can add extra blank lines</li>
 *   <li>Element ordering — partners must appear before partnerships in the XML</li>
 * </ul>
 *
 * <p>To use, change the {@code classname} in {@code config.xml}:
 * <pre>
 * &lt;partnerships classname="org.openas2.partner.ManagedXMLPartnershipFactory" .../&gt;
 * </pre>
 */
public class ManagedXMLPartnershipFactory extends XMLPartnershipFactory {

    private Logger logger = LoggerFactory.getLogger(ManagedXMLPartnershipFactory.class);

    @Override
    public void storePartnership() throws OpenAS2Exception {
        String fn = getFilename();

        // Backup rotation — find next available backup filename
        DecimalFormat df = new DecimalFormat("0000000");
        long l = 0;
        File f = null;
        while (true) {
            f = new File(fn + '.' + df.format(l));
            if (f.exists() == false) {
                break;
            }
            l++;
        }

        logger.info("Backing up " + fn + " to " + f.getName());

        File fr = new File(fn);
        fr.renameTo(f);

        // Write from in-memory state
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<partnerships>\n");

        // Write all partners first
        Map<String, Object> partners = getPartners();
        for (Map.Entry<String, Object> entry : partners.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, String> partnerAttrs = (Map<String, String>) entry.getValue();
            sb.append("    <partner");
            for (Map.Entry<String, String> attr : partnerAttrs.entrySet()) {
                sb.append(" ").append(attr.getKey()).append("=\"")
                  .append(escapeXmlAttr(attr.getValue())).append("\"");
            }
            sb.append("/>\n");
        }

        // Write all partnerships
        List<Partnership> partnerships = getPartnerships();
        for (Partnership partnership : partnerships) {
            sb.append("    <partnership name=\"")
              .append(escapeXmlAttr(partnership.getName())).append("\">\n");

            // Sender — reference partner by name
            String senderName = partnership.getSenderID(Partnership.PID_NAME);
            if (senderName != null) {
                sb.append("        <sender name=\"")
                  .append(escapeXmlAttr(senderName)).append("\"/>\n");
            }

            // Receiver — reference partner by name
            String receiverName = partnership.getReceiverID(Partnership.PID_NAME);
            if (receiverName != null) {
                sb.append("        <receiver name=\"")
                  .append(escapeXmlAttr(receiverName)).append("\"/>\n");
            }

            // PollerConfig — preserved from DOM since it's not stored on Partnership at runtime
            Element pollerElem = findPollerConfigInDom(partnership.getName());
            if (pollerElem != null) {
                sb.append("        ").append(serializePollerConfig(pollerElem)).append("\n");
            }

            // Attributes
            Map<String, String> attrs = partnership.getAttributes();
            for (Map.Entry<String, String> attr : attrs.entrySet()) {
                sb.append("        <attribute name=\"")
                  .append(escapeXmlAttr(attr.getKey())).append("\" value=\"")
                  .append(escapeXmlAttr(attr.getValue())).append("\"/>\n");
            }

            sb.append("    </partnership>\n");
        }

        sb.append("</partnerships>\n");

        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(new File(fn)), StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
        } catch (IOException e) {
            throw new WrappedException(e);
        }
    }

    private String escapeXmlAttr(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private Element findPollerConfigInDom(String partnershipName) {
        if (getPartnershipsXml() == null) {
            return null;
        }
        NodeList children = getPartnershipsXml().getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "partnership".equals(child.getNodeName())) {
                Element elem = (Element) child;
                String name = elem.getAttribute("name");
                if (partnershipName.equals(name)) {
                    Node pollerNode = XMLUtil.findChildNode(elem, Partnership.PCFG_POLLER);
                    if (pollerNode != null && pollerNode instanceof Element) {
                        return (Element) pollerNode;
                    }
                    return null;
                }
            }
        }
        return null;
    }

    private String serializePollerConfig(Element elem) {
        StringBuilder sb = new StringBuilder();
        sb.append("<pollerConfig");
        NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            sb.append(" ").append(attr.getNodeName()).append("=\"")
              .append(escapeXmlAttr(attr.getNodeValue())).append("\"");
        }
        sb.append("/>");
        return sb.toString();
    }
}
