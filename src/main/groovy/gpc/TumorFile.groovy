package gpc

import com.imsweb.layout.LayoutFactory
import com.imsweb.layout.record.fixed.FixedColumnsField
import com.imsweb.layout.record.fixed.FixedColumnsLayout
import com.imsweb.naaccrxml.PatientFlatReader
import com.imsweb.naaccrxml.PatientReader
import com.imsweb.naaccrxml.entity.Item
import com.imsweb.naaccrxml.entity.Patient
import com.imsweb.naaccrxml.entity.Tumor
import gpc.DBConfig.Task
import groovy.sql.BatchingPreparedStatementWrapper
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.docopt.Docopt
import tech.tablesaw.api.*
import tech.tablesaw.columns.Column
import tech.tablesaw.columns.strings.StringFilters

import javax.annotation.Nullable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.CRC32

import static TumorOnt.load_data_frame

@CompileStatic
@Slf4j
class TumorFile {
    // see also: buildUsageDoc groovy task
    static final String usage = TumorOnt.resourceText('usage.txt')

    static void main(String[] args) {
        DBConfig.CLI cli
        Closure<Properties> getProps = { String name ->
            Properties ps = new Properties()
            new File(name).withInputStream { ps.load(it) }
            if (ps.containsKey('db.passkey')) {
                ps.setProperty('db.password', cli.mustGetEnv(ps.getProperty('db.passkey')))
            }
            ps
        }
        cli = new DBConfig.CLI(new Docopt(usage).parse(args),
                { String name -> getProps(name) },
                { int it -> System.exit(it) },
                { String url, Properties ps -> DriverManager.getConnection(url, ps) })

        run_cli(cli)
    }

    static void run_cli(DBConfig.CLI cli) {
        DBConfig cdw = cli.account()

        //noinspection GroovyUnusedAssignment -- avoids weird cast error
        Task work = null
        String task_id = cli.arg("--task-id", "task123")  // TODO: replace by date, NPI?

        if (cli.flag('tumor-table')) {
            work = new NAACCR_Extract(cdw, task_id,
                    [cli.urlProperty("naaccr.flat-file")],
                    cli.property("naaccr.tumor-table"),
            )
        } else if (cli.flag('tumor-files')) {
            work = new NAACCR_Extract(
                    cdw, task_id,
                    cli.files('NAACCR_FILE'), cli.property("naaccr.tumor-table"))
        } else if (cli.flag('summary')) {
            work = new NAACCR_Summary(cdw, task_id,
                    [cli.urlProperty("naaccr.flat-file")],
                    cli.property("naaccr.tumor-table"),
                    cli.property("naaccr.stats-table"),
            )
        } else if (cli.flag('facts')) {
            final upload = new I2B2Upload(
                    cli.property("i2b2.star-schema", null),
                    cli.intArg('--upload-id'),
                    cli.arg('--obs-src'),
                    cli.arg('--mrn-src'))
            work = new NAACCR_Facts(cdw,
                    upload,
                    cli.urlProperty("naaccr.flat-file"),
                    cli.property("naaccr.tumor-table"))
        } else if (cli.flag('load-layouts')) {
            work = new LoadLayouts(cdw, cli.arg('--layout-table'))
        } else if (cli.flag('run') || cli.flag('query')) {
            Loader.run_cli(cli)
            return
        } else {
            TumorOnt.run_cli(cli)
            return
        }

        if (work && !work.complete()) {
            work.run()
        }
    }

    static int line_count(URL input) {
        int count = 0
        input.withInputStream { InputStream lines ->
            new Scanner(lines).useDelimiter("\r\n|\n") each { String it ->
                count += 1
            }
        }
        count
    }

    private static class TableBuilder {
        String table_name
        String task_id

        boolean complete(DBConfig account) {
            boolean done = false
            account.withSql { Sql sql ->
                // TODO: avoid noisy warnings by using JDBC metadata to check whether table exists
                try {
                    final row = sql.firstRow("select count(*) from ${table_name} where task_id = ?.task_id",
                            [task_id: task_id])
                    if (row != null && (row[0] as int) >= 1) {
                        log.info("complete: ${row[0]} rows with task_id = $task_id")
                        done = true
                    }
                } catch (SQLException problem) {
                    log.warn("not complete: $problem")
                }
                null
            }
            done
        }

        void build(Sql sql, Table data) {
            data.addColumns(constS('task_id', data, task_id))
            dropIfExists(sql, table_name)
            load_data_frame(sql, table_name, data)
            log.info("inserted ${data.rowCount()} rows into ${table_name}")
        }


        @Deprecated
        /**
         * no more need to insert from Table
         */
        void reset(Sql sql, Table data) {
            dropIfExists(sql, table_name)
            log.debug("creating table ${table_name}")
            sql.execute(TumorOnt.SqlScript.create_ddl(table_name, data.columns()))
        }

        @Deprecated
        /**
         * no more need to insert from Table
         */
        void appendChunk(Sql sql, Table data) {
            TumorOnt.append_data_frame(data, table_name, sql)
        }
    }

    private static StringColumn constS(String name, Table t, String val) {
        StringColumn.create(name, [val] * t.rowCount() as String[])
    }

    // TODO: fix Summary / stats design. Use generated facts?
    static class NAACCR_Summary implements Task {
        final TableBuilder tb
        final DBConfig cdw
        final NAACCR_Extract extract_task

        NAACCR_Summary(DBConfig cdw, String task_id,
                       List<URL> flat_files, String extract_table, String stats_table) {
            tb = new TableBuilder(task_id: task_id, table_name: stats_table)
            this.cdw = cdw
            extract_task = new NAACCR_Extract(cdw, task_id, flat_files, extract_table)
        }

        boolean complete() { tb.complete(cdw) }

        void run() {
            cdw.withSql { Sql sql ->
                final loadTable = { String name, Table data -> load_data_frame(sql, name.toUpperCase(), data, true) }
                loadTable('section', TumorOnt.NAACCR_I2B2.per_section)
                loadTable('record_layout', record_layout)
                loadTable('tumor_item_type', TumorOnt.NAACCR_I2B2.tumor_item_type)
                Loader ld = new Loader(sql)
                URL script = TumorFile.getResource('heron_load/data_char_sim.sql')
                ld.runScript(script)
            }
        }
    }


    static class NAACCR_Extract implements Task {
        final TableBuilder tb
        final DBConfig cdw
        final List<URL> flat_files

        NAACCR_Extract(DBConfig cdw, String task_id, List<URL> flat_files, String extract_table) {
            this.cdw = cdw
            tb = new TableBuilder(task_id: task_id, table_name: extract_table)
            this.flat_files = flat_files
        }

        @Override
        boolean complete() { tb.complete(cdw) }

        @Override
        void run() {
            Table fields = TumorOnt.pcornet_fields.copy()

            // PCORnet spec doesn't include MRN column, but we need it for patient mapping.
            Row patid = fields.appendRow()
            patid.setInt('item', 20)
            patid.setString('FIELD_NAME', 'PATIENT_ID_NUMBER_N20')

            cdw.withSql { Sql sql ->
                dropIfExists(sql, tb.table_name)
                int encounter_num = 0
                flat_files.eachWithIndex { flat_file, ix ->
                    final create = ix == 0
                    final update = ix == flat_files.size() - 1
                    encounter_num = TumorFile.NAACCR_Extract.loadFlatFile(
                            sql, new File(flat_file.path), tb.table_name, tb.task_id, fields,
                            encounter_num, create, update)
                }
            }
        }

        static int loadFlatFile(Sql sql, File flat_file, String table_name, String task_id, Table fields,
                                int encounter_num = 0,
                                boolean create = true, boolean update = true,
                                String varchar = "VARCHAR2", int batchSize = 64) {
            // TODO: look up "VARCHAR2" using getTypeInfo
            // https://docs.oracle.com/javase/7/docs/api/java/sql/DatabaseMetaData.html#getTypeInfo()
            FixedColumnsLayout layout = theLayout(flat_file)
            final source_cd = flat_file.name

            final colInfo = columnInfo(fields, layout, varchar)
            String ddl = """
            create table ${table_name} (
              source_cd varchar(50),
              encounter_num int,
              patient_num int,
              task_id ${varchar}(1024),
              ${colInfo.collect { it.colDef }.join(",\n  ")},
              observation_blob clob
            )
            """

            String dml = """
            insert into ${table_name} (
              source_cd, encounter_num, observation_blob,
              ${colInfo.collect { it.name }.join(',\n  ')})
            values (:source_cd, :encounter_num, :observation_blob, ${colInfo.collect { it.param }.join('\n,  ')})
            """
            flat_file.withInputStream { InputStream naaccr_text_lines ->
                if (create) {
                    sql.execute(ddl)
                }
                sql.withBatch(batchSize, dml) { BatchingPreparedStatementWrapper ps ->
                    new Scanner(naaccr_text_lines).useDelimiter("\r\n|\n") each { String line ->
                        encounter_num += 1
                        final record = fixedRecord(colInfo, line)
                        ps.addBatch([
                                source_cd       : source_cd as Object,
                                encounter_num   : encounter_num as Object,
                                observation_blob: line as Object
                        ] + record)
                        if (encounter_num % 1000 == 0) {
                            log.info('inserted {} records', encounter_num)
                        }
                    }
                }
                log.info("inserted ${encounter_num} records into $table_name")
            }
            // only fill in task_id after all rows are done
            if (update) {
                log.info("updating task_id in ${table_name}")
                sql.execute("update ${table_name} set task_id = ?.task_id",
                        [task_id: task_id])
            }

            encounter_num
        }

        static Map<String, Object> fixedRecord(List<Map> colInfo, String line) {
            colInfo.collect {
                final start = it.start as int - 1
                final length = it.length as int
                [it.name, line.substring(start, start + length).trim()]
            }.findAll { (it[1] as String) > '' }.collectEntries { it }
        }

        static FixedColumnsLayout theLayout(File flat_file) {
            final layouts = LayoutFactory.discoverFormat(flat_file)
            if (layouts.size() < 1) {
                throw new RuntimeException("cannot discover format of ${flat_file}")
            } else if (layouts.size() > 1) {
                throw new RuntimeException("ambiguous format: ${flat_file}: ${layouts.collect { it.layoutId }}.join(',')")
            }
            final layout = LayoutFactory.getLayout(layouts[0].layoutId) as FixedColumnsLayout
            log.info('{}: layout {}', flat_file.name, layout.layoutName)
            layout
        }

        static List<Map> columnInfo(Table fields, FixedColumnsLayout layout, String varchar) {
            fields.collect {
                final num = it.getInt("item")
                final name = it.getString("FIELD_NAME")
                final item = layout.getFieldByNaaccrItemNumber(num)
                [num: num, name: name, item: item]
            }.findAll {
                if (it.item == null) {
                    log.warn("item not found in ${layout.layoutId}: ${it.num} ${it.name}")
                }
                it.item != null
            }.collect {
                final item = it.item as FixedColumnsField
                [
                        name  : it.name,
                        start : item.start,
                        length: item.length,
                        colDef: "${it.name} ${varchar}(${item.length}) ",
                        param : ":${it.name}",
                ]
            } as List<Map>
        }

        def <V> V withRecords(Closure<V> thunk) {
            V result
            for (flat_file in flat_files) {
                log.info("reading records from ${flat_file}")
                result = thunk(new InputStreamReader(flat_file.openStream()))
            }
            result
        }

        void updatePatientNum(Sql sql, I2B2Upload upload, String patient_ide_expr) {
            final dml = """
                update ${tb.table_name} tr
                set tr.patient_num = (
                    select pm.patient_num
                    from ${upload.patientMapping} pm
                    where pm.patient_ide_source = ?.source
                    and pm.patient_ide = ${patient_ide_expr}
                    )
            """
            sql.execute(dml, [source: upload.patient_ide_source])
        }

        Map<String, Integer> getPatientMapping(Sql sql, patient_ide_col) {
            String q = """
                select ${patient_ide_col} ide, patient_num from ${tb.table_name}
                where patient_num is not null
            """.trim()
            log.info('{}', q)
            final rows = sql.rows(q)
            log.info('got {} rows', rows.size())
            Map<String, Integer> toPatientId = rows.collectEntries { [it.ide, it.patient_num] }
            toPatientId
        }
    }

    /**
     * Drop a table using JDBC metadata to check whether it exists first.
     * @param table_qname either schema.name or just name
     * @return true iff the table existed (and hence was dropped)
     */
    static boolean dropIfExists(Sql sql, String table_qname) {
        if (tableExists(sql, table_qname)) {
            sql.execute("drop table ${table_qname}" as String)
        }
    }

    static boolean tableExists(Sql sql, String table_qname) {
        final parts = table_qname.split('\\.')
        String schema = parts.length == 2 ? parts[0].toUpperCase() : null
        String table_name = parts[-1].toUpperCase()
        final results = sql.connection.getMetaData().getTables(null, schema, table_name, null)
        if (results.next()) {
            return true
        }
        return false
    }

    @Deprecated
    /**
     * Make a per-tumor table for use in encounter_mapping etc.
     * @Deprecated: we sequentially number tumors as we load them from the flat file now.
     */
    static class NAACCR_Visits implements Task {
        static final String table_name = "NAACCR_TUMORS"
        int encounter_num_start

        final TableBuilder tb
        private final DBConfig cdw
        private final NAACCR_Extract extract_task

        NAACCR_Visits(DBConfig cdw, String task_id, List<URL> flat_files, String extract_table, int start) {
            tb = new TableBuilder(task_id: task_id, table_name: table_name)
            this.cdw = cdw
            extract_task = new NAACCR_Extract(cdw, task_id, flat_files, extract_table)
            encounter_num_start = start
        }

        boolean complete() { tb.complete(cdw) }

        void run() {
            Table tumors = _data(encounter_num_start)
            cdw.withSql { Sql sql ->
                tb.build(sql, tumors)
            }
        }

        Table _data(int encounter_num_start) {
            extract_task.withRecords { Reader naaccr_text_lines ->
                Table tumors = TumorKeys.with_tumor_id(
                        TumorKeys.pat_tmr(naaccr_text_lines))
                tumors = TumorKeys.with_rownum(
                        tumors, encounter_num_start)
                tumors
            }
        }
    }

    @Deprecated
    /**
     * Make a per-patient table for use in patient_mapping etc.
     * @Deprecated: we add a patient_num column to the PCORnet TUMOR table instead now.
     */
    static class NAACCR_Patients implements Task {
        static String table_name = "NAACCR_PATIENTS"
        static String patient_ide_source = 'SMS@kumed.com'
        static String schema = 'NIGHTHERONDATA'

        final TableBuilder tb
        private final DBConfig cdw
        private final NAACCR_Extract extract_task

        NAACCR_Patients(DBConfig cdw, String task_id, List<URL> flat_files, String extract_table) {
            tb = new TableBuilder(task_id: task_id, table_name: table_name)
            this.cdw = cdw
            extract_task = new NAACCR_Extract(cdw, task_id, flat_files, extract_table)

        }

        boolean complete() { tb.complete(cdw) }

        void run() {
            cdw.withSql { Sql sql ->
                Table patients = _data(sql)
                tb.build(sql, patients)
            }
        }

        Table _data(Sql cdwdb) {
            extract_task.withRecords { Reader naaccr_text_lines ->
                Table patients = TumorKeys.patients(naaccr_text_lines)
                TumorKeys.with_patient_num(patients, cdwdb, schema, patient_ide_source)
            }
        }
    }

    static class NAACCR_Facts implements Task {
        static final int encounter_num_start = 2000000 // TODO: sync with TUMOR extract. build facts from CLOB?
        static final String mrnItem = 'patientIdNumber'
        static final String patient_ide_col = 'PATIENT_ID_NUMBER_N20'
        static final String patient_ide_expr = "trim(leading '0' from tr.${patient_ide_col})"

        final TableBuilder tb
        private final DBConfig cdw
        private final URL flat_file
        private final NAACCR_Extract extract_task
        private final I2B2Upload upload

        NAACCR_Facts(DBConfig cdw, I2B2Upload upload, URL flat_file, String extract_table) {
            final task_id = "upload_id_${upload.upload_id}"  // TODO: transition from task_id to upload_id
            tb = new TableBuilder(task_id: task_id, table_name: "OBSERVATION_FACT_${upload.upload_id}")
            this.flat_file = flat_file
            this.cdw = cdw
            this.upload = upload
            extract_task = new NAACCR_Extract(cdw, task_id, [flat_file], extract_table)
        }

        boolean complete() { tb.complete(cdw) }

        void run() {
            final flat_file = new File(flat_file.path)

            cdw.withSql { Sql sql ->
                extract_task.updatePatientNum(sql, upload, patient_ide_expr)
                final toPatientNum = extract_task.getPatientMapping(sql, patient_ide_col)

                makeTumorFacts(
                        flat_file, encounter_num_start,
                        sql, mrnItem, toPatientNum,
                        upload)
            }
        }

        @Deprecated
        void runMemDB() {
            final DBConfig mem = DBConfig.inMemoryDB("Facts")
            boolean firstChunk = true
            cdw.withSql { Sql sql ->
                mem.withSql { Sql memdb ->
                    extract_task.withRecords { Reader naaccr_text_lines ->
                        read_fwf(naaccr_text_lines) { Table extract ->
                            Table item = ItemObs.make(memdb, extract)
                            // TODO: Table seer = SEER_Recode.make(sql, extract)
                            // TODO: Table ssf = SiteSpecificFactors.make(sql, extract)
                            // TODO: item.append(seer).append(ssf)
                            if (firstChunk) {
                                tb.reset(sql, item)
                                firstChunk = false
                            }
                            tb.appendChunk(sql, item)
                            memdb.execute('drop all objects')
                            return null
                        }
                    }
                }
            }
        }
    }

    static class I2B2Upload {
        final String schema
        final int upload_id
        final String sourcesystem_cd
        final String patient_ide_source

        I2B2Upload(@Nullable String schema, int upload_id, String sourcesystem_cd, String patient_ide_source) {
            this.schema = schema
            this.upload_id = upload_id
            this.sourcesystem_cd = sourcesystem_cd
            this.patient_ide_source = patient_ide_source
        }

        String getFactTable() {
            qname("OBSERVATION_FACT_${upload_id}")
        }

        String getPatientMapping() {
            qname("PATIENT_MAPPING")
        }

        private String qname(String object_name) {
            (schema == null ? '' : (schema + '.')) + object_name
        }

        static private String colDefs
        static private String colNames
        static private String params
        static {
            final obs_cols = [
                    [name: "ENCOUNTER_NUM", type: "int", null: false],
                    [name: "PATIENT_NUM", type: "int", null: false],
                    [name: "CONCEPT_CD", type: "VARCHAR(50)", null: false],
                    [name: "PROVIDER_ID", type: "VARCHAR(50)", null: false],
                    // TODO: check timestamp vs. date re partition exchange; switch to create table as?
                    [name: "START_DATE", type: "timestamp", null: false],
                    [name: "MODIFIER_CD", type: "VARCHAR(100)", null: false],
                    [name: "INSTANCE_NUM", type: "int", null: false],
                    [name: "VALTYPE_CD", type: "VARCHAR(50)"],
                    [name: "TVAL_CHAR", type: "VARCHAR(4000)"],
                    [name: "NVAL_NUM", type: "float"],
                    [name: "END_DATE", type: "timestamp"],
                    [name: "UPDATE_DATE", type: "timestamp"],
                    [name: "DOWNLOAD_DATE", type: "timestamp"],
                    [name: "IMPORT_DATE", type: "timestamp"],
                    [name: "SOURCESYSTEM_CD", type: "VARCHAR(50)"],
                    [name: "UPLOAD_ID", type: "int"],
            ]
            colDefs = obs_cols.collect { "${it.name} ${it.type} ${it.null == false ? "not null" : ""}" }.join(",\n  ")
            colNames = obs_cols.collect { it.name }.join(",\n  ")
            params = obs_cols.collect {
                it.name == 'IMPORT_DATE' ? 'current_timestamp' : "?.${it.name}".toLowerCase()
            }.join(",\n  ")
        }

        String getFactTableDDL() {
            """
            create table ${factTable} (
                ${colDefs},
                primary key (
                    ENCOUNTER_NUM, CONCEPT_CD, PROVIDER_ID, START_DATE, MODIFIER_CD, INSTANCE_NUM)
            )
            """
        }

        String getInsertStatement() {
            """
            insert into ${getFactTable()} (
                ${colNames}
            ) values (
                ${params}
            )
            """
        }

        static final not_null = '@'
    }

    static int makeTumorFacts(File flat_file, int encounter_num,
                              Sql sql, String mrnItem, Map<String, Integer> toPatientNum,
                              I2B2Upload upload,
                              boolean include_phi = false) {
        log.info("fact DML: {}", upload.insertStatement)

        final layout = TumorFile.NAACCR_Extract.theLayout(flat_file)

        final itemInfo = TumorOnt.NAACCR_I2B2.tumor_item_type.iterator().collect {
            final num = it.getInt('naaccrNum')
            final lf = layout.getFieldByNaaccrItemNumber(num)
            if (lf != null) {
                assert num == lf.naaccrItemNum
                assert it.getString('naaccrId') == lf.name
            }
            [num       : num, layout: lf,
             valtype_cd: it.getString('valtype_cd')]
        }.findAll { it.layout != null && (include_phi || !(it.valtype_cd as String).contains('i')) }

        final patIdField = layout.getFieldByName(mrnItem)
        final dxDateField = layout.getFieldByName('dateOfDiagnosis')
        final dateFields = [
                'dateOfBirth', 'dateOfDiagnosis', 'dateOfLastContact',
                'dateCaseCompleted', 'dateCaseLastChanged', 'dateCaseReportExported'
        ].collect { layout.getFieldByName(it) }

        dropIfExists(sql, upload.factTable)
        sql.execute(upload.factTableDDL)
        sql.withBatch(256, upload.insertStatement) { ps ->
            int fact_qty = 0
            new Scanner(flat_file).useDelimiter("\r\n|\n") each { String line ->
                encounter_num += 1
                String patientId = fieldValue(patIdField, line)
                if (!toPatientNum.containsKey(patientId)) {
                    log.warn('tumor {}: cannot find {} in patient_mapping', encounter_num, patientId)
                    return
                }
                final patient_num = toPatientNum[patientId]
                Map<String, LocalDate> dates = dateFields.collectEntries { FixedColumnsField dtf ->
                    [dtf.name, TumorFile.parseDate(fieldValue(dtf, line))]
                }
                if (dates.dateOfDiagnosis == null) {
                    log.info('tumor {} patient {}: cannot parse dateOfDiagnosis: {}',
                            encounter_num, patientId, fieldValue(dxDateField, line))
                    return
                }
                itemInfo.each { item ->
                    Map record
                    final field = item.layout as FixedColumnsField
                    try {
                        record = itemFact(encounter_num, patient_num, line, dates,
                                field, item.valtype_cd as String,
                                upload.sourcesystem_cd)
                    } catch (badItem) {
                        log.warn('tumor {} patient {}: cannot make fact for item {}: {}',
                                encounter_num, patientId, field.name, badItem.toString())
                    }
                    if (record != null) {
                        ps.addBatch(record)
                        fact_qty += 1
                    }
                }
                if (encounter_num % 20 == 0) {
                    log.info('tumor {}: {} facts', encounter_num, fact_qty)
                }
            }
        }
        // only fill in upload_id after all rows are done
        sql.execute("update ${upload.factTable} set upload_id = ?.upload_id".toString(), [upload_id: upload.upload_id])
        encounter_num
    }

    static String fieldValue(FixedColumnsField field, String line) {
        line.substring(field.start - 1, field.start + field.length - 1).trim()
    }

    static Map itemFact(int encounter_num, int patient_num, String line, Map<String, LocalDate> dates,
                        FixedColumnsField fixed, String valtype_cd,
                        String sourcesystem_cd) {
        final value = fieldValue(fixed, line)
        if (value == '') {
            return null
        }
        final nominal = valtype_cd == '@' ? value : ''
        LocalDate start_date
        if (valtype_cd == 'D') {
            if (value == '99999999') {
                // "blank" date value
                return null
            }
            start_date = TumorFile.parseDate(value)
            if (start_date == null) {
                log.warn('tumor {} patient {}: cannot parse {}: [{}]',
                        encounter_num, patient_num, fixed.name, value)
                return null
            }
        } else {
            start_date = fixed.section == 'Follow-up/Recurrence/Death' ? dates.dateOfLastContact :
                    dates.dateOfDiagnosis
        }
        String concept_cd = "NAACCR|${fixed.naaccrItemNum}:${nominal}"
        assert concept_cd.length() <= 50
        Double num
        if (valtype_cd == 'N') {
            try {
                num = Double.parseDouble(value)
            } catch (badNum) {
                log.warn('tumor {} patient {}: cannot parse number {}: [{}]',
                        encounter_num, patient_num, fixed.name, value)
                return null
            }
        }
        final update_date = [
                dates.dateCaseLastChanged, dates.dateCaseCompleted,
                dates.dateOfLastContact, dates.dateOfDiagnosis,
        ].find { it != null }
        [
                encounter_num  : encounter_num,
                patient_num    : patient_num,
                concept_cd     : concept_cd,
                provider_id    : I2B2Upload.not_null,
                start_date     : start_date,
                modifier_cd    : I2B2Upload.not_null,
                instance_num   : 1,
                valtype_cd     : valtype_cd,
                tval_char      : valtype_cd == 'T' ? value : null,
                nval_num       : valtype_cd == 'N' ? num : null,
                end_date       : start_date,
                update_date    : update_date,
                download_date  : dates.dateCaseReportExported,
                sourcesystem_cd: sourcesystem_cd,
                upload_id      : null,
        ]
    }

    @Deprecated
    /**
     * obsolete in favor of makeTumorFacts
     */
    static class ItemObs {
        static final TumorOnt.SqlScript script = new TumorOnt.SqlScript('naaccr_txform.sql',
                TumorOnt.resourceText('heron_load/naaccr_txform.sql'),
                [
                        new Tuple2('tumor_item_value', ['naaccr_obs_raw', 'tumor_item_type']),
                        new Tuple2('tumor_reg_facts', ['record_layout', 'section']),
                ])

        static Table make(Sql sql, Table extract) {
            final item_ty = TumorOnt.NAACCR_I2B2.tumor_item_type

            Table raw_obs = DataSummary.stack_obs(extract, item_ty, TumorKeys.key4 + TumorKeys.dtcols)
            raw_obs = naaccr_dates(raw_obs, TumorKeys.dtcols)
            DBConfig.parseDateExInstall(sql)

            final views = TumorOnt.create_objects(
                    sql, script, [
                    naaccr_obs_raw : raw_obs,
                    tumor_item_type: item_ty,
                    record_layout  : record_layout,
                    section        : TumorOnt.NAACCR_I2B2.per_section,
            ])

            return views.values().last()(sql)
        }
    }

    @Deprecated
    static long _stable_hash(String text) {
        final CRC32 out = new CRC32()
        final byte[] bs = text.getBytes('UTF-8')
        out.update(bs, 0, bs.size())
        out.value

    }

    @Deprecated
    /**
     * obsolete in favor of makeTumorFacts
     */
    static void read_fwf(Reader lines, Closure<Void> f,
                         int chunkSize = 200) {
        Table empty = Table.create(TumorKeys.required_cols.collect { StringColumn.create(it) }
                as Collection<Column<?>>)
        Table data = empty.copy()
        PatientReader reader = new PatientFlatReader(lines)
        Patient patient = reader.readPatient()

        final ensureCol = { Item item ->
            if (!data.columnNames().contains(item.naaccrId)) {
                data.addColumns(StringColumn.create(item.naaccrId, data.rowCount()))
                empty.addColumns(StringColumn.create(item.naaccrId, 0))
            }
        }

        while (patient != null) {
            patient.getTumors().each { Tumor tumor ->
                if (data.rowCount() >= chunkSize) {
                    f(data)
                    data = empty.copy()
                }
                data.appendRow()
                final consume = { Item it ->
                    if (it.value.trim() > '') {
                        ensureCol(it)
                        data.row(data.rowCount() - 1).setString(it.naaccrId, it.value)
                    }
                }
                patient.items.each consume
                tumor.items.each consume
                reader.getRootData().items.each consume
            }
            patient = reader.readPatient()
        }
        if (data.rowCount() > 0) {
            f(data)
        }
    }

    @Deprecated
    /**
     * obsolete in favor of makeTumorFacts
     */
    static class TumorKeys {
        static List<String> pat_ids = ['patientSystemIdHosp', 'patientIdNumber']
        static List<String> pat_attrs = pat_ids + ['dateOfBirth', 'dateOfLastContact', 'sex', 'vitalStatus']
        static final List<String> tmr_ids = ['tumorRecordNumber']
        static final List<String> tmr_attrs = tmr_ids + [
                'dateOfDiagnosis',
                'sequenceNumberCentral', 'sequenceNumberHospital', 'primarySite',
                'ageAtDiagnosis', 'dateOfInptAdm', 'dateOfInptDisch', 'classOfCase',
                'dateCaseInitiated', 'dateCaseCompleted', 'dateCaseLastChanged',
        ]
        static List<String> report_ids = ['naaccrRecordVersion', 'npiRegistryId']
        static List<String> report_attrs = report_ids + ['dateCaseReportExported']
        static List<String> dtcols = ['dateOfBirth', 'dateOfDiagnosis', 'dateOfLastContact',
                                      'dateCaseCompleted', 'dateCaseLastChanged']
        static List<String> key4 = [
                'patientSystemIdHosp',  // NAACCR stable patient ID
                'tumorRecordNumber',    // NAACCR stable tumor ID
                'patientIdNumber',      // patient_mapping
                'abstractedBy',         // IDEA/YAGNI?: provider_id
        ]
        static List<String> required_cols = (pat_attrs + tmr_attrs + report_attrs + key4.drop(3) +
                dtcols.collect { it + 'Flag' })

        static Table pat_tmr(Reader naaccr_text_lines) {
            _pick_cols(tmr_attrs + pat_attrs + report_attrs, naaccr_text_lines)
        }

        static Table patients(Reader naaccr_text_lines) {
            _pick_cols(pat_attrs + report_attrs, naaccr_text_lines)
        }

        private static Table _pick_cols(List<String> attrs, Reader lines) {
            Table pat_tmr = Table.create(attrs.collect { it -> StringColumn.create(it) }
                    as Collection<Column<?>>)
            PatientReader reader = new PatientFlatReader(lines)
            Patient patient = reader.readPatient()
            while (patient != null) {
                Row patientRow = pat_tmr.appendRow()
                attrs.each { String naaccrId ->
                    patientRow.setString(naaccrId, patient.getItemValue(naaccrId))
                }
                patient = reader.readPatient()
            }
            pat_tmr = naaccr_dates(pat_tmr, pat_tmr.columnNames().findAll { it.startsWith('date') })
            pat_tmr
        }

        static Table with_tumor_id(Table data,
                                   String name = 'recordId',
                                   List<String> extra = ['dateOfDiagnosis',
                                                         'dateCaseCompleted'],
                                   // keep recordId length consistent
                                   String extra_default = null) {
            // ISSUE: performance: add encounter_num column here?
            if (extra_default == null) {
                extra_default = '0000-00-00'
            }
            StringColumn id_col = data.stringColumn('patientIdNumber')
                    .join('', data.stringColumn('tumorRecordNumber'))
            extra.forEach { String it ->
                StringColumn sc = data.column(it).copy().asStringColumn()
                sc.set((sc as StringFilters).isMissing(), extra_default)
                id_col = id_col.join('', sc)
            }
            data = data.copy()
            data.addColumns(id_col.setName(name))
            data
        }

        static Table with_rownum(Table tumors, int start,
                                 String new_col = 'encounter_num',
                                 String key_col = 'recordId') {
            tumors.sortOn(key_col)
            tumors.addColumns(IntColumn.indexColumn(new_col, tumors.rowCount(), start))
            tumors
        }

        static void export_patient_ids(Table df, Sql cdw,
                                       String tmp_table = 'NAACCR_PMAP',
                                       String id_col = 'patientIdNumber') {
            log.info("writing $id_col to $tmp_table")
            Table pat_ids = df.select(id_col).dropDuplicateRows()
            load_data_frame(cdw, tmp_table, pat_ids)
        }

        static Table with_patient_num(Table df, Sql cdw, String schema,
                                      String source,
                                      String tmp_table = 'NAACCR_PMAP',
                                      String id_col = 'patientIdNumber') {
            export_patient_ids(df, cdw, tmp_table, id_col)
            Table src_map = null
            cdw.query("""(
                select ea."${id_col}", pmap.PATIENT_NUM as "patient_num"
                from ${tmp_table} ea
                join ${schema}.PATIENT_MAPPING pmap
                on pmap.patient_ide_source = ?.source
                and ltrim(pmap.patient_ide, '0') = ltrim(ea."${id_col}", '0')
                )""", [source: source]) { ResultSet results ->
                src_map = Table.read().db(results)
            }
            Table out = df.joinOn(id_col).leftOuter(src_map)
            out.removeColumns(id_col)
            out
        }
    }

    @Deprecated
    /**
     * obsolete in favor of theLayout
     */
    static final FixedColumnsLayout layout18 = LayoutFactory.getLayout(LayoutFactory.LAYOUT_ID_NAACCR_18_INCIDENCE) as FixedColumnsLayout
    @Deprecated
    /**
     * obsolete in favor of theLayout
     */
    static final Table record_layout = TumorOnt.fromRecords(
            layout18.getAllFields().collect { FixedColumnsField it ->
                [('long-label')     : it.longLabel,
                 start              : it.start,
                 length             : it.length,
                 ('naaccr-item-num'): it.naaccrItemNum,
                 section            : it.section,
                 grouped            : it.subFields != null && it.subFields.size() > 0
                ] as Map
            })

    @Deprecated
    static Table naaccr_dates(Table df, List<String> date_cols,
                              boolean keep = false) {
        final orig_cols = df.columnNames()
        for (String dtname : date_cols) {
            final strname = dtname + '_'
            final StringColumn strcol = df.column(dtname).copy().asStringColumn().setName(strname)
            df = df.replaceColumn(dtname, strcol)
            df.addColumns(naaccr_date_col(strcol).setName(dtname))
        }
        if (!keep) {
            // ISSUE: df.select(*orig_cols) uses spread which doesn't work with CompileStatic
            df = Table.create(orig_cols.collect { String it -> df.column(it) })
        }
        df
    }

    @Deprecated
    static DateColumn naaccr_date_col(StringColumn sc) {
        String name = sc.name()
        sc = sc.trim().concatenate('01019999').substring(0, 8)
        // type of StringColumn.map(fun, creator) is too fancy for groovy CompileStatic
        final data = sc.asList().collect { parseDate(it) }
        DateColumn.create(name, data)
    }

    static LocalDate parseDate(String txt) {
        LocalDate value
        int nch = txt.length()
        String full = nch == 4 ? txt + '0101' : nch == 6 ? txt + '01' : txt
        try {
            value = LocalDate.parse(full, DateTimeFormatter.BASIC_ISO_DATE) // 'yyyyMMdd'
        } catch (DateTimeParseException ignored) {
            value = null
        }
        value
    }

    static class DataSummary {
        /* TODO: fix sd col?
        DoubleColumn sd = out.doubleColumn('sd')
        sd.set((sd as NumericColumn).isMissing(), 0 as Double)
        */

        static Table stack_obs(Table data, Table ty,
                               List<String> id_vars = [],
                               List<String> valtype_cds = ['@', 'D', 'N'],
                               String var_name = 'naaccrId',
                               String id_col = 'recordId') {
            final StringColumn value_vars = ty
                    .where(ty.stringColumn('valtype_cd').isIn(valtype_cds)
                            & ty.stringColumn('naaccrId').isNotIn(id_vars)).stringColumn('naaccrId')
            data = data.copy().addColumns(IntColumn.indexColumn(id_col, data.rowCount(), 0))
            final Table df = melt(data, value_vars.asList(), [id_col] + id_vars, var_name)
            df.where(df.stringColumn('value').isLongerThan(0))
        }

        static Table melt(Table data, List<String> value_vars, List<String> id_vars,
                          String var_name, String value_col = 'value') {
            final Table entity = Table.create(id_vars.collect { String it -> data.column(it) })
            final dataColNames = data.columnNames()
            List<String> value_vars_absent = value_vars.findAll { String v -> !dataColNames.contains(v) }
            final value_vars_present = value_vars.findAll { String v -> dataColNames.contains(v) }
            if (value_vars_absent.size() > 0) {
                log.warn("cols missing in melt(): $value_vars_absent")
            }
            final List<Column> dataCols = value_vars_present.collect { String it -> data.column(it) }
            Table out = null
            dataCols.forEach { Column valueColumn ->
                Table slice = entity.copy()
                StringColumn attribute = StringColumn.create(var_name, [valueColumn.name()] * data.rowCount())
                slice.addColumns(attribute, valueColumn.copy().setName(value_col))
                if (out == null) {
                    out = slice
                } else {
                    out = out.append(slice)
                }
            }
            out
        }
    }


    static class LoadLayouts implements DBConfig.Task {
        final private DBConfig account
        final String table_name

        LoadLayouts(DBConfig account, String table_name) {
            this.account = account
            this.table_name = table_name
        }

        boolean complete() {
            account.withSql { Sql sql ->
                def fieldsPerVersion
                try {
                    fieldsPerVersion = sql.rows(
                            "select layoutVersion, count(*) field_qty from ${table_name} group by layoutVersion".toString())
                    log.info("load-layout complete? {}", fieldsPerVersion)
                } catch (SQLException oops) {
                    log.warn("failed to check layout records: {}", oops.toString())
                    return false
                }
                (fieldsPerVersion.findAll { it.field_qty as int >= 100 }).size() >= 3
            }
        }

        List<String> mapColumns(Closure<String> f) {
            final pretty_long = 64
            [
                    [COLUMN_NAME: 'naaccrItemNum', DATA_TYPE: java.sql.Types.INTEGER],
                    [COLUMN_NAME: 'section', DATA_TYPE: java.sql.Types.VARCHAR, COLUMN_SIZE: pretty_long],
                    [COLUMN_NAME: 'name', DATA_TYPE: java.sql.Types.VARCHAR, COLUMN_SIZE: pretty_long],
                    [COLUMN_NAME: 'longLabel', DATA_TYPE: java.sql.Types.VARCHAR, COLUMN_SIZE: pretty_long],
                    [COLUMN_NAME: 'shortLabel', DATA_TYPE: java.sql.Types.VARCHAR, COLUMN_SIZE: pretty_long],
                    [COLUMN_NAME: 'startPos', DATA_TYPE: java.sql.Types.INTEGER],
                    [COLUMN_NAME: 'endPos', DATA_TYPE: java.sql.Types.INTEGER],
                    [COLUMN_NAME: 'length', DATA_TYPE: java.sql.Types.INTEGER],
                    [COLUMN_NAME: 'trim', DATA_TYPE: java.sql.Types.BOOLEAN],
                    [COLUMN_NAME: 'padChar', DATA_TYPE: java.sql.Types.VARCHAR, COLUMN_SIZE: 1],
                    [COLUMN_NAME: 'align', DATA_TYPE: java.sql.Types.VARCHAR, COLUMN_SIZE: 16], // actually enum: LEFT, ...
                    [COLUMN_NAME: 'defaultValue', DATA_TYPE: java.sql.Types.VARCHAR, COLUMN_SIZE: pretty_long],
                    // subFields?
            ].collect { f(it.COLUMN_NAME, it.DATA_TYPE, it.COLUMN_SIZE) }
        }

        void run() {
            final layouts = [
                    LayoutFactory.getLayout(LayoutFactory.LAYOUT_ID_NAACCR_12) as FixedColumnsLayout,
                    LayoutFactory.getLayout(LayoutFactory.LAYOUT_ID_NAACCR_14) as FixedColumnsLayout,
                    LayoutFactory.getLayout(LayoutFactory.LAYOUT_ID_NAACCR_16) as FixedColumnsLayout,
                    LayoutFactory.getLayout(LayoutFactory.LAYOUT_ID_NAACCR_18) as FixedColumnsLayout,
            ]
            account.withSql { Sql sql ->
                dropIfExists(sql, table_name)
                sql.execute(ddl(sql))
                final String stmt = """
                    insert into ${table_name} (layoutVersion,
                    ${mapColumns { String name, _t, _s -> name }.join(",\n  ")})
                    values (?.layoutVersion, ${mapColumns { name, _t, _s -> "?.${name}".toString() }.join(",\n  ")})
                """.trim()
                log.info("layout insert: {}", stmt)
                layouts.each { layout ->

                    layout.allFields.each { field ->
                        final Map params = [
                                layoutVersion: layout.layoutVersion,
                                naaccrItemNum: field.naaccrItemNum,
                                section      : field.section,
                                name         : field.name,
                                longLabel    : field.longLabel,
                                shortLabel   : field.shortLabel,
                                startPos     : field.start,
                                endPos       : field.end,
                                length       : field.length,
                                trim         : field.trim,
                                padChar      : field.padChar,
                                align        : field.align.toString(),
                                defaultValue : field.defaultValue
                        ]
                        sql.executeInsert(params, stmt)
                    }
                }
            }
        }

        Map<Integer, String> typeNames(Connection connection) {
            final dbTypes = connection.metaData.getTypeInfo()
            Map<Integer, String> toName = [:]
            while (dbTypes.next()) {
                // println([DATA_TYPE    : dbTypes.getInt('DATA_TYPE'),
                //          TYPE_NAME    : dbTypes.getString('TYPE_NAME'),
                //          CREATE_PARAMS: dbTypes.getString('CREATE_PARAMS')])
                final ty = dbTypes.getInt('DATA_TYPE')
                if (!toName.containsKey(ty)) {
                    toName[ty] = dbTypes.getString('TYPE_NAME')
                }
            }
            if (toName[java.sql.Types.BOOLEAN] == null) {
                toName[java.sql.Types.BOOLEAN] = toName[java.sql.Types.INTEGER]
            }
            toName
        }

        String ddl(Sql sql) {
            final toName = typeNames(sql.connection)
            final colDefs = mapColumns { name, ty, size ->
                "${name} ${toName[ty]}" + ({ s -> s != null ? "(${s})" : "" })(size)
            }
            """
             create table ${table_name} (
                layoutVersion ${toName[java.sql.Types.VARCHAR]}(3),
                ${colDefs.join(",\n  ")}
            )
            """.trim()
        }
    }
}
