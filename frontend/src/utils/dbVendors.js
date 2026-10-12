// Database products the server can reach (domain/model/integration/DbVendor;
// MySQL through the MariaDB driver) with their default ports, and the TLS
// modes offered — shared by the integration connection wizard and the
// ingestion database export (v1.2.0: vendor/host form, no raw JDBC URL).

export const DB_VENDORS = [
  { value: 'POSTGRESQL', label: 'PostgreSQL', port: 5432 },
  { value: 'ORACLE', label: 'Oracle', port: 1521 },
  { value: 'SQLSERVER', label: 'SQL Server', port: 1433 },
  { value: 'MARIADB', label: 'MariaDB / MySQL', port: 3306 },
];

export const SSL_MODES = ['require', 'verify-ca', 'verify-full', 'disable'];

export const defaultPort = (vendor) => DB_VENDORS.find((v) => v.value === vendor)?.port ?? '';
