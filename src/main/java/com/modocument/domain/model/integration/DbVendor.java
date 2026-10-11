package com.modocument.domain.model.integration;

/**
 * Database products a DATABASE connection may target (ws25). MySQL is reached
 * through the MariaDB Connector/J (LGPL), never the GPL MySQL driver.
 */
public enum DbVendor {
    POSTGRESQL,
    ORACLE,
    SQLSERVER,
    MARIADB
}
