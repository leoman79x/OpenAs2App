-- Schema for DbPartnershipFactory
-- Run this script against your database before configuring OpenAS2 to use DbPartnershipFactory.

CREATE TABLE partners (
    name                 VARCHAR(255) NOT NULL PRIMARY KEY,
    as2_id               VARCHAR(255),
    x509_alias           VARCHAR(255),
    x509_alias_fallback  VARCHAR(255),
    email                VARCHAR(255)
);

CREATE TABLE partnerships (
    name           VARCHAR(255) NOT NULL PRIMARY KEY,
    sender_name    VARCHAR(255) NOT NULL,
    receiver_name  VARCHAR(255) NOT NULL,
    FOREIGN KEY (sender_name) REFERENCES partners(name),
    FOREIGN KEY (receiver_name) REFERENCES partners(name)
);

CREATE TABLE partnership_attributes (
    partnership_name VARCHAR(255) NOT NULL,
    attr_name        VARCHAR(255) NOT NULL,
    attr_value       VARCHAR(4000),
    PRIMARY KEY (partnership_name, attr_name),
    FOREIGN KEY (partnership_name) REFERENCES partnerships(name) ON DELETE CASCADE
);

CREATE TABLE partnership_poller_config (
    partnership_name VARCHAR(255) NOT NULL,
    attr_name        VARCHAR(255) NOT NULL,
    attr_value       VARCHAR(4000),
    PRIMARY KEY (partnership_name, attr_name),
    FOREIGN KEY (partnership_name) REFERENCES partnerships(name) ON DELETE CASCADE
);
