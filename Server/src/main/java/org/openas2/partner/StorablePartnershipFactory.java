package org.openas2.partner;

import org.openas2.OpenAS2Exception;

import java.util.Map;

/**
 * Interface for partnership factories that support mutation operations (add/update/delete)
 * and persistence. Both XML-backed and database-backed factories implement this so that
 * command classes can work with either without casting to a specific type.
 */
public interface StorablePartnershipFactory {
    void storePartnership() throws OpenAS2Exception;

    void addPartner(Map<String, String> attributes) throws OpenAS2Exception;
    void deletePartner(String name) throws OpenAS2Exception;
    void updatePartner(String name, Map<String, String> attributes) throws OpenAS2Exception;

    void addPartnership(String name, String senderName, String receiverName,
                        Map<String, String> attributes, Map<String, String> pollerConfig) throws OpenAS2Exception;
    void deletePartnership(String name) throws OpenAS2Exception;
    void updatePartnership(String name, String senderName, String receiverName,
                           Map<String, String> attributes, Map<String, String> pollerConfig) throws OpenAS2Exception;
}
