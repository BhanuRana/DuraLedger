// Generates jOOQ classes from the *real* schema: throwaway Postgres container -> Flyway migrations ->
// jOOQ codegen over JDBC. Run by gmavenplus in the generate-sources phase (see pom.xml).
//
// Why not jOOQ's DDLDatabase? It replays the SQL on an embedded H2, which can't parse PL/pgSQL
// functions or partial indexes, so the generated code would drift from the real schema.
//
// Skipped when no migration is newer than the last successful generation (stamp file), so normal
// builds don't pay for a container. Force with -Djooq.codegen.force=true.

import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.*
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

def base = project.basedir
def migrations = new File(base, "src/main/resources/db/migration")
def outDir = new File(base, "target/generated-sources/jooq")
def stamp = new File(outDir, ".codegen-stamp")

def newestMigration = migrations.listFiles().collect { it.lastModified() }.max() ?: 0L
if (!Boolean.getBoolean("jooq.codegen.force") && stamp.exists() && stamp.lastModified() >= newestMigration) {
    log.info("jOOQ codegen: up to date, skipping")
    return
}

def postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
postgres.start()
try {
    Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("filesystem:" + migrations.absolutePath)
            .load()
            .migrate()

    GenerationTool.generate(new Configuration()
            .withLogging(Logging.WARN)
            .withJdbc(new Jdbc()
                    .withDriver("org.postgresql.Driver")
                    .withUrl(postgres.jdbcUrl)
                    .withUser(postgres.username)
                    .withPassword(postgres.password))
            .withGenerator(new Generator()
                    .withDatabase(new Database()
                            .withName("org.jooq.meta.postgres.PostgresDatabase")
                            .withInputSchema("public")
                            .withOutputSchemaToDefault(true)
                            .withExcludes("flyway_schema_history|reject_ledger_mutation|check_transaction_balanced"))
                    .withGenerate(new Generate()
                            .withJavaTimeTypes(true)
                            .withRecords(true)
                            .withPojos(false))
                    .withTarget(new Target()
                            .withPackageName("com.duraledger.ledger.jooq")
                            .withDirectory(outDir.absolutePath))))

    stamp.text = new Date().toString()
    log.info("jOOQ codegen: generated from Postgres at ${postgres.jdbcUrl}")
} finally {
    postgres.stop()
}
