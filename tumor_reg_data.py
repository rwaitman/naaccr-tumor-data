# ---
# jupyter:
#   jupytext:
#     formats: ipynb,py:hydrogen
#     text_representation:
#       extension: .py
#       format_name: hydrogen
#       format_version: '1.2'
#       jupytext_version: 1.2.1
#   kernelspec:
#     display_name: Python 3
#     language: python
#     name: python3
# ---

# %% [markdown]
# # NAACCR Tumor Registry Data
#
# This is both a notebook and a module, sync'd using [jupytext][]. See also
#
#   - README for motivation and usage
#   - CONTRIBUTING for coding style etc.
#
# [jupytext]: https://github.com/mwouts/jupytext

# %% [markdown]
# ### Preface: PyData Tools: Pandas, PySpark
#
#

# %%
# python stdlib
from gzip import GzipFile
from importlib import resources as res
from sys import stderr
from typing import Iterable
from xml.etree import ElementTree as XML
import logging
import datetime


# %%
# 3rd party code: PyData
from pyspark.sql import functions as func
from pyspark.sql import types as ty
from pyspark.sql.dataframe import DataFrame
from pyspark.sql.functions import col
import numpy as np
import pandas as pd

# %% [markdown]
#  - **ISSUE**: naaccr_xml stuff is currently symlink'd to a git
#    clone; `naaccr_xml_res` corresponds to
#    https://github.com/imsweb/naaccr-xml/blob/master/src/main/resources/

# %%
# 3rd party: naaccr-xml
import naaccr_xml_res  # ISSUE: symlink noted above
import naaccr_xml_samples
import naaccr_xml_xsd

# %%
# this project
from sql_script import SqlScript
from test_data.flat_file import naaccr_read_fwf
from tumor_reg_ont import create_object, DataDictionary
import heron_load


# %%
log = logging.getLogger(__name__)

if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO, stream=stderr)
    log.info('NAACCR exploration...')


# %%
log.info('%s', dict(pandas=pd.__version__))

# %% [markdown]
# ## I/O Access: local files, Spark / Hive metastore

# %% [markdown]
# In a notebook context, we have `__name__ == '__main__'`.
#
# Otherwise, we maintain ocap discipline (see CONTRIBUTING)
# and don't import powerful objects.

# %%
CAN_IO = __name__ == '__main__'

if CAN_IO:
    def _cwd():
        # ISSUE: ambient
        from pathlib import Path
        return Path('.')

    cwd = _cwd()
    log.info('cwd: %s', cwd)

# %% [markdown]
# The `spark` global is available when we launch as
# `PYSPARK_DRIVER_PYTHON=jupyter PYSPARK_DRIVER_PYTHON_OPTS=notebook
#    pyspark ...`.

# %%
CAN_IO and spark

# %%
CAN_IO and log.info('spark web UI: %s', spark.sparkContext.uiWebUrl)


# %% [markdown]
# ## `naaccr-xml` Data Dictionary

# %%
class XSD:
    uri = 'http://www.w3.org/2001/XMLSchema'
    ns = {'xsd': uri}


class NAACCR1:
    dd13_xsd = XML.parse(res.open_text(
        naaccr_xml_xsd, 'naaccr_dictionary_1.3.xsd'))

    ndd180 = XML.parse(res.open_text(
        naaccr_xml_res, 'naaccr-dictionary-180.xml'))

    data_xsd = XML.parse(res.open_text(
        naaccr_xml_xsd, 'naaccr_data_1.3.xsd'))

    s100x = XML.parse(GzipFile(fileobj=res.open_binary(
        naaccr_xml_samples, 'naaccr-xml-sample-v180-incidence-100.xml.gz')))

    item_xsd = data_xsd.find(
        './/xsd:complexType[@name="itemType"]/xsd:simpleContent/xsd:extension',
        XSD.ns)

    ItemDef = dd13_xsd.find('.//xsd:element[@name="ItemDef"]', XSD.ns)

    uri = 'http://naaccr.org/naaccrxml'
    ns = {'n': uri}


def eltSchema(xsd_complex_type,
              simpleContent=False):
    decls = xsd_complex_type.findall('xsd:attribute', XSD.ns)
    fields = [
        ty.StructField(
            name=d.attrib['name'],
            dataType=ty.IntegerType() if d.attrib['type'] == 'xsd:integer'
            else ty.BooleanType() if d.attrib['type'] == 'xsd:boolean'
            else ty.StringType(),
            nullable=d.attrib.get('use') != 'required',
            # IDEA/YAGNI?: use pd.Categorical for xsd:enumeration
            # e.g. tns:parentType
            metadata=d.attrib)
        for d in decls]
    if simpleContent:
        fields = fields + [ty.StructField('value', ty.StringType(), False)]
    return ty.StructType(fields)


def xmlDF(spark, schema, doc, path, ns,
          eltRecords=None,
          simpleContent=False):
    if eltRecords is None:
        eltRecords = eltDict
    data = (record
            for elt in doc.iterfind(path, ns)
            for record in eltRecords(elt, schema, simpleContent))
    return spark.createDataFrame(data, schema)


def eltDict(elt, schema,
            simpleContent=False):
    out = {k: int(v) if isinstance(schema[k].dataType, ty.IntegerType)
           else bool(v) if isinstance(schema[k].dataType, ty.BooleanType)
           else v
           for (k, v) in elt.attrib.items()}
    if simpleContent:
        out['value'] = elt.text
    # print("typed", s2s, out)
    yield out


def ddictDF(spark):
    return xmlDF(spark,
                 schema=eltSchema(NAACCR1.ItemDef.find('*')),
                 doc=NAACCR1.ndd180,
                 path='./n:ItemDefs/n:ItemDef',
                 ns=NAACCR1.ns)


CAN_IO and ddictDF(spark).limit(5).toPandas().set_index('naaccrId')

# %% [markdown]
# ## NAACCR XML Data

# %%
eltSchema(NAACCR1.item_xsd, simpleContent=True)


# %%
def tumorDF(spark, doc):
    rownum = 0
    ns = {'n': 'http://naaccr.org/naaccrxml'}

    def tumorItems(tumorElt, schema, simpleContent=True):
        nonlocal rownum
        assert simpleContent
        rownum += 1
        for item in tumorElt.iterfind('./n:Item', ns):
            # print(tumorElt, item)
            yield dict(next(eltDict(item, schema, simpleContent)),
                       rownum=rownum)
        # TODO: ../n:Item for Patient items, ../../n:Item for NaaccrData

    itemSchema = eltSchema(NAACCR1.item_xsd, simpleContent=True)
    rownumField = ty.StructField('rownum', ty.IntegerType(), False)
    tumorItemSchema = ty.StructType([rownumField] + itemSchema.fields)
    data = xmlDF(spark, schema=tumorItemSchema, doc=doc, path='.//n:Tumor',
                 eltRecords=tumorItems,
                 ns={'n': 'http://naaccr.org/naaccrxml'},
                 simpleContent=True)
    return data


CAN_IO and (tumorDF(spark, NAACCR1.s100x)
            .toPandas().sort_values(['naaccrId', 'rownum']).head(20))


# %%
def naaccr_pivot(ddict, skinny, key_cols,
                 pivot_on='naaccrId', value_col='value',
                 start='startColumn'):
    groups = skinny.select(pivot_on, value_col, *key_cols).groupBy(*key_cols)
    wide = groups.pivot(pivot_on).agg(func.first(value_col))
    start_by_id = {id: start
                   for (id, start) in ddict.select(pivot_on, start).collect()}
    sorted_cols = sorted(wide.columns, key=lambda id: start_by_id.get(id, -1))
    return wide.select(sorted_cols)


CAN_IO and (naaccr_pivot(ddictDF(spark),
                         tumorDF(spark, NAACCR1.s100x),
                         ['rownum'])
            .limit(3).toPandas())


# %% [markdown]
# ## tumor_item_type: numeric /  date / nominal / text; identifier?

# %%
def tumor_item_type(spark, cache):
    DataDictionary.make_in(spark, cache)

    create_object('t_item',
                  res.read_text(heron_load, 'naaccr_concepts_load.sql'),
                  spark)

    create_object('tumor_item_type',
                  res.read_text(heron_load, 'naaccr_txform.sql'),
                  spark)
    spark.catalog.cacheTable('tumor_item_type')
    return spark.table('tumor_item_type')


CAN_IO and (tumor_item_type(spark, cwd / 'naaccr_ddict')
            .limit(5).toPandas().set_index(['ItemNbr', 'xmlId']))

# %%
CAN_IO and spark.sql('''
select valtype_cd, count(*)
from tumor_item_type
group by valtype_cd
''').toPandas().set_index('valtype_cd')

# %% [markdown]
# ## NAACCR Flat File v18

# %% [markdown]
# ### Warning! Identified Data!

# %%
#@@@!hostname

# %%
tr_file = cwd / '/d1/naaccr/donotuse_2019_02_naaccr' / 'NCDB_Export_3.31.22_PM.txt'
tr_file.exists()

# %%
x = spark.read.text(str(tr_file))
x.rdd.getNumPartitions()

# %%
x.limit(5).toPandas()


# %%
def non_blank(df):
    return df[[
        col for col in df.columns
        if (df[col].str.strip() > '').any()
    ]]


# %%
syn_records = pd.read_pickle('test_data/,syn_records_TMP.pkl')
non_blank(syn_records[coded_items[
    (coded_items.sectionid == 1) &
    (coded_items.xmlId.isin(syn_records.columns))].xmlId.values.tolist()]).tail(15)

# %%
stuff = pd.read_pickle('test_data/,test-stuff.pkl')
stuff.iloc[0]['lines']

# %%
ndd = DataDictionary.make_in(spark, cwd / 'naaccr_ddict')
test_data_coded = naaccr_read_fwf(spark.read.text('test_data/,test_data.flat.txt'), ndd.record_layout)
test_data_coded.limit(5).toPandas()

# %%
xp = test_data_coded.select(coded_items[coded_items.sectionid == 1].xmlId.values.tolist()).limit(15).toPandas()


xp[[
    col for col in xp.columns
    if (xp[col].str.strip() > '').any()
]]

# %%
coded_items = tumor_item_type.where("valtype_cd = '@'").toPandas()
coded_items.tail()

# %%
stuff.iloc[0].lines[:41]

# %%
spark.read.text(str(tr_file)).take(1)[0].value[:41]

# %%

def naaccr_read_fwf(flat_file, record_layout):
    fields = [
        func.substring(flat_file.value, item.start, item.length).alias(item.xmlId)
        for item in record_layout.collect()
        if not item.xmlId.startswith('reserved')
    ]
    return flat_file.select(fields)


naaccr_text_lines = spark.read.text(str(tr_file))

extract = naaccr_read_fwf(naaccr_text_lines, ndd.record_layout)
extract.createOrReplaceTempView('naaccr_extract')
# extract.explain()
extract.limit(5).toPandas()

# %%
xp = extract.sample(False, 0.01).select(coded_items[coded_items.sectionid == 1].xmlId.values.tolist()).limit(15).toPandas()

xp[[
    col for col in xp.columns
    if (xp[col].str.strip() > '').any()
]]

# %% [markdown]
# ## Synthesizing Data
#
# Let's take a NAACCR file and gather stats on it so that we can synthesize data with similar characteristics.

# %% [markdown]
# ### Characteristics of data from our NAACCR file

# %% [markdown]
# Now let's add an id column and make a long-skinny from the wide data, starting with nominals:

# %%


def melt(df: DataFrame,
         id_vars: Iterable[str], value_vars: Iterable[str],
         var_name: str = "variable", value_name: str = "value") -> DataFrame:
    """Convert :class:`DataFrame` from wide to long format."""
    # ack: user6910411 Jan 2017 https://stackoverflow.com/a/41673644

    # Create array<struct<variable: str, value: ...>>
    _vars_and_vals = func.array(*(
        func.struct(func.lit(c).alias(var_name), func.col(c).alias(value_name))
        for c in value_vars))

    # Add to the DataFrame and explode
    _tmp = df.withColumn("_vars_and_vals", func.explode(_vars_and_vals))

    cols = id_vars + [
        func.col("_vars_and_vals")[x].alias(x) for x in [var_name, value_name]]
    return _tmp.select(*cols)


# %%
def stack_nominals(data, ty,
                   var_name='xmlId',
                   id_col='record'):
    value_vars = [row.xmlId for row in ty.where(ty.valtype_cd == '@').collect()]
    df = melt(data.withColumn(id_col, func.monotonically_increasing_id()),
              value_vars=value_vars, id_vars=[id_col], var_name=var_name)
    return df.where(func.trim(df.value) > '')

tumors_eav = stack_nominals(extract, tumor_item_type)
tumors_eav.createOrReplaceTempView('tumors_eav')

tumors_eav.limit(10).toPandas().set_index(['record', 'xmlId'])

# %%
tumors_eav.limit(10).foreachPartition(lambda p: len(p))

# %%
tumors_eav.cache()

# %%
create_object('data_agg_naaccr',
              res.read_text(heron_load, 'data_char_sim.sql'),
              spark)

# spark.sql("select * from data_agg_naaccr limit 10").explain()
spark.catalog.cacheTable('data_agg_naaccr')

# %%
data_agg_naaccr = spark.sql('''
select * from data_agg_naaccr
''').toPandas().set_index(['itemnbr', 'xmlId', 'value'])

# %%
data_agg_naaccr = data_agg_naaccr.sort_index()
(10)

# %%
data_agg_naaccr = spark.sql('''
select s.sectionId, rl.section, nom.*
from data_agg_naaccr nom
join (select xmlId, section from record_layout) rl on rl.xmlId = nom.xmlId
join (select sectionId, section from section) s on s.section = rl.section
''').toPandas().set_index(['sectionId', 'section', 'itemnbr', 'xmlId', 'value']).sort_index()
data_agg_naaccr.to_csv(cwd / 'data_agg_naaccr.csv')
data_agg_naaccr.head(10)

# %% [markdown]
# ## Synthesizing Data
#
# Let's take a NAACCR file and gather stats on it so that we can synthesize data with similar characteristics.
#
# **ISSUE**: combine with OMOP cohort based on syn-puf?

# %%
simulated_entity = spark.createDataFrame([(ix,) for ix in range(1, 500)], ['case_index'])
simulated_entity.createOrReplaceTempView('simulated_entity')
simulated_entity.limit(5).toPandas()

# %%
create_object('data_char_naaccr',
              res.read_text(test_data, 'data_char_sim.sql'),
              spark)
create_object('nominal_cdf',
              res.read_text(test_data, 'data_char_sim.sql'),
              spark)
create_object('simulated_naaccr_nom',
              res.read_text(test_data, 'data_char_sim.sql'),
              spark)

# %%
x = spark.sql('''
select * from nominal_cdf
''')
x.limit(10).toPandas()

# %%
spark.catalog.cacheTable('simulated_naaccr_nom')

# %% [markdown]
# For **nominal data**, what's the prevalence of each value of each variable?
#
# Let's compare observed with synthesized:

# %%
#@@%matplotlib inline

# %%
stats = data_agg_naaccr.reset_index()
seq = stats[stats.itemnbr == 380].set_index('value')

print(seq[['itemnbr', 'xmlId', 'freq', 'present', 'pct']].head())
seq.pct.astype(float).plot.pie();

# %%
seq_sim = spark.sql('''
select *
from simulated_naaccr_nom
where itemnbr = 380
''').toPandas().set_index('case_index')

seq_sim_by_val = seq_sim.groupby('value').count()

print(seq_sim_by_val.itemnbr * 100 / len(seq_sim))
seq_sim_by_val.itemnbr.plot.pie();

# %%
col_order = { row.xmlId: row.start for row in 
              spark.sql("select start, xmlId from record_layout").collect()}
list(col_order.items())[:10]

# %%
sim_records_nom = spark.sql('''
select data.case_index, data.xmlId, data.value
from simulated_naaccr_nom data
join record_layout rl on rl.xmlId = data.xmlId
join section on rl.section = section.section
where sectionId = 1
order by case_index, rl.start
''').toPandas()
sim_records_nom = sim_records_nom.pivot(index='case_index', columns='xmlId', values='value')
for col in sim_records_nom.columns:
    sim_records_nom[col] = sim_records_nom[col].astype('category')
sim_records_nom = sim_records_nom[sorted(sim_records_nom.columns, key=lambda xid: col_order[xid])]
sim_records_nom.head(15)

# %%
x = extract.limit(15).toPandas()[sim_records_nom.columns]
x

# %%
x.histologyIcdO2.iloc[3]

# %%
sim_records_nom.dateConclusiveDxFlag.iloc[0] is np.nan

# %%
sim_records_nom.dtypes

# %% [markdown]
# For dates, how long before/after diagnosis?
#
# For diagnosis, how long ago?

# %%
stats[stats.valtype_cd == 'D'].head(3)

# %%
sim = pd.read_sql('''select count(*), case_index, itemnbr from simulated_naaccr group by case_index, itemnbr having count(*) > 1''', tr1)
sim.head(20)

# %%
pd.read_sql('''
            select count(*), case_index, itemnbr from simulated_naaccr
            group by case_index, itemnbr
            order by 1 desc
''', tr1).head()

# %% [markdown]
# ## ???

# %%
tr_chunk1 = extract.limit(100)
tr_chunk1.limit(10).toPandas()

# %% [markdown]
# ## NAACCR Dates

# %%
def naaccr_dates(df, date_cols, keep=False):
    orig_cols = df.columns
    for dtcol in date_cols:
        strcol = dtcol + '_'
        df = df.withColumnRenamed(dtcol, strcol)
        dt = func.to_date(func.unix_timestamp(df[strcol], 'yyyyMMdd').cast('timestamp'))
        df = df.withColumn(dtcol, dt)
    if not keep:
        df = df.select(orig_cols)
    return df

naaccr_dates(extract.select(['dateOfDiagnosis', 'dateOfLastContact']),
             ['dateOfDiagnosis', 'dateOfLastContact'], keep=True).limit(10).toPandas()

# %% [markdown]
# ### Strange dates: TODO?

# %%
x = naaccr_dates(extract.select(['dateOfDiagnosis']),
             ['dateOfDiagnosis'], keep=True)
x = x.withColumn('dtlen', func.length(func.trim(x.dateOfDiagnosis_)))
x = x.where(x.dtlen > 0)
x = x.withColumn('cc', func.substring(func.trim(x.dateOfDiagnosis_), 1, 2))

x.where(~(x.cc.isin(['19', '20'])) |
        ((x.dtlen < 8) & (x.dtlen > 0))).toPandas().groupby(['dtlen', 'cc']).count()


# %% [markdown]
# ## Unique key columns
#
#  - `patientSystemIdHosp` - "This provides a stable identifier to link back to all reported tumors for a patient. It also serves as a reliable linking identifier; useful when central registries send follow-up information back to hospitals. Other identifiers such as social security number and medical record number, while useful, are subject to change and are thus less useful for this type of record linkage."
#  - `tumorRecordNumber` - "Description: A system-generated number assigned to each tumor. The number should never change even if the tumor sequence is changed or a record (tumor) is deleted.
#     Rationale: This is a unique number that identifies a specific tumor so data can be linked. "Sequence Number" cannot be used as a link because the number is changed if a report identifies an earlier tumor or if a tumor record is deleted."
#
# Turns out to be not enough:

# %%
def dups(df_spark, key_cols):
    df_pd = df_spark.toPandas().sort_values(key_cols)
    df_pd['dup'] = df_pd.duplicated(key_cols, keep=False)
    return df_pd[df_pd.dup]

key1 = ['patientSystemIdHosp', 'tumorRecordNumber']

dups(extract.select('sequenceNumberCentral', 'dateOfDiagnosis', 'dateCaseCompleted', *key1), key1).set_index(key1)

# %%
pat_ids = ['patientSystemIdHosp', 'patientIdNumber' , 'accessionNumberHosp']
pat_attrs = pat_ids + ['dateOfBirth', 'dateOfLastContact', 'sex', 'vitalStatus']
tmr_ids = ['tumorRecordNumber']
tmr_attrs = tmr_ids + ['dateOfDiagnosis',
                       'sequenceNumberCentral', 'sequenceNumberHospital', 'primarySite',
          'ageAtDiagnosis', 'dateOfInptAdm', 'dateOfInptDisch', 'classOfCase',
          'dateCaseInitiated', 'dateCaseCompleted', 'dateCaseLastChanged']
report_ids = ['naaccrRecordVersion', 'npiRegistryId']
report_attrs = report_ids + ['dateCaseReportExported']

pat_tmr = naaccr_text_lines.select(
    *[naaccr_col(naaccr_text_lines.value, xmlId)
      for xmlId in (tmr_attrs + pat_attrs + report_attrs)
    ]
)
nodate = func.lit('0000-00-00')  # ISSUE: keep recordId length consistent?
pat_tmr.createOrReplaceTempView('pat_tmr')
pat_tmr = naaccr_dates(pat_tmr, [c for c in pat_tmr.columns if c.startswith('date')])

def with_tumor_id(data,
                  name='recordId',
                  extra=['dateOfDiagnosis', 'dateCaseCompleted'],
                  extra_default=func.lit('0000-00-00')):
    return data.withColumn('recordId',
                           func.concat(data.patientSystemIdHosp,
                                       data.tumorRecordNumber,
                                       *[func.coalesce(data[col], extra_default)
                                         for col in extra]))
# pat_tmr.cache()
pat_tmr = with_tumor_id(pat_tmr)
pat_tmr

# %%
pat_tmr.limit(15).toPandas()

# %% [markdown]
# ## Diagnosed before born??

# %%
x = naaccr_dates(pat_tmr, ['dateOfDiagnosis', 'dateOfBirth']).toPandas()
x['ddx_orig'] = pat_tmr.select('dateOfDiagnosis', 'dateOfDiagnosisFlag').toPandas().dateOfDiagnosis
x = x[x.ageAtDiagnosis.str.startswith('-')]
x['age2'] = (x.dateOfDiagnosis - x.dateOfBirth).dt.days / 365.25
x[['ageAtDiagnosis', 'age2', 'ddx_orig', 'dateOfDiagnosis', 'dateOfDiagnosisFlag', 'dateOfBirth']].sort_values('ddx_orig')


# %%
dx_age = pat_tmr_pd.groupby('ageAtDiagnosis')
dx_age[['dateOfBirth']].count()
#dx_age = dx_age[dx_age != '999']
#dx_age.unique()

#dx_age = dx_age.astype(int)
#dx_age.describe()

# %%
dx_age.hist()

# %%
pat_tmr_pd[pat_tmr_pd.patientSystemIdHosp == '01002923']


# %% [markdown]
# ## Coded observations

# %%
def naccr_coded(records, ty):
    value_vars = [row.xmlId for row in ty.where(ty.valtype_cd == '@').collect()]
    dtcols = ['dateOfBirth', 'dateOfDiagnosis', 'dateOfLastContact', 'dateCaseCompleted', 'dateCaseLastChanged']
    dated = naaccr_dates(records, dtcols)
    df = melt(dated,
              [
                  'patientSystemIdHosp',  # NAACCR stable patient ID
                  'tumorRecordNumber',    # NAACCR stable tumor ID
                  'patientIdNumber',      # patient_mapping
                  'abstractedBy',         # provider_id? ISSUE.
              ] + dtcols,
              value_vars, var_name='xmlId', value_name='code')
    return df.where(func.trim(df.code) > '')


coded = naccr_coded(extract, tumor_item_type)
# coded.cache()  # avoid 'Too many open files' https://stackoverflow.com/questions/25707629
coded = with_tumor_id(coded)

coded.createOrReplaceTempView('tumor_coded_value')
# coded.explain()
coded.limit(10).toPandas().set_index(['recordId', 'xmlId'])

# %%
SqlScript.create_object('tumor_reg_coded_facts', cwd / 'heron_load' / 'naaccr_txform.sql', spark)

tumor_reg_coded_facts = spark.sql('select * from tumor_reg_coded_facts')
tumor_reg_coded_facts.printSchema()
tumor_reg_coded_facts.limit(5).toPandas()

# %%
def naaccr_col(value, xmlId):
    # AMBIENT: spark
    # MAGIC: record_layout
    # INJECTION: xmlId
    [item] = spark.sql(f"select * from record_layout where xmlId = '{xmlId}'").collect()
    return func.substring(value, item.start, item.length).alias(xmlId)

naaccr_col(naaccr_text_lines.value, 'patientSystemIdHosp')

# %% [markdown]
# ## Oracle DB Access

# %%
if CAN_IO:
    from os import environ
    log.info(environ['PYSPARK_SUBMIT_ARGS'])


# %%
if CAN_IO:
    def set_pw(name='CDW'):
        from os import environ
        from getpass import getpass
        password = getpass(name)
        environ[name] = password

    set_pw()


# %%
def cdw(io, table,
        driver="oracle.jdbc.OracleDriver",
        url='jdbc:oracle:thin:@localhost:1521:nheronA1',
        **kw_args):
    #@@ from os import environ
    return io.jdbc(url, table,
          properties={"user": environ['LOGNAME'], "password": environ['CDW'],
                      "driver": driver}, **kw_args)
    
jdbcDF2 = cdw(spark.read, "global_name")
jdbcDF2.toPandas()

# %% [markdown]
# **ISSUE**: column name capitalization: `concept_cd` vs. `CONCEPT_CD`, `dateOfDiagnosis` vs. `DATEOFDIAGNOSIS` vs. `DATE_OF_DIAGNOSIS`.

# %%
cdw(tumor_reg_coded_facts.write, "TUMOR_REG_CODED_FACTS", mode='overwrite')

# %% [markdown]
# ## registry table
#
# Information about the registry; or rather: the export from the registry.

# %%
registry0 = tr_chunk1.select(
    ['naaccrRecordVersion', 'npiRegistryId', 'dateCaseReportExported']
).limit(1)
registry0.createOrReplaceTempView('registry0')

registry = spark.sql('''
select cast(naaccrRecordVersion as int) naaccrRecordVersion
     , npiRegistryId
     , to_date(cast(unix_timestamp(dateCaseReportExported, 'yyyyMMdd')
                    as timestamp)) dateCaseReportExported
from registry0
''')
registry.printSchema()
registry.createOrReplaceTempView('registry')

spark.sql('select * from registry').toPandas()

# %% [markdown]
# ## @@@@@@@@@@

# %%

def _raw_data(folder='data-raw'):
    # I'd like to treat these as code, i.e. design-time artifacts,
    # but pkg_resouces isn't cooperating.
    from pathlib import Path
    return Path(folder)


def noop(chars):
    return chars


def from_date(chars):
    if len(chars) not in (6, 7):
        raise ValueError(chars)
    return datetime.datetime.strptime(
        chars[1:] if len(chars) == 7 and chars[0] in '09' else chars, '%y%m%d')


class RecordFormat(object):
    def __init__(self, data_raw,
                 version=18):
        self.items = pd.read_csv(
            data_raw / 'record-formats' / ('version-%s.csv' % version)).set_index('item')
        self.field_info = pd.read_csv(
            data_raw / 'field_info.csv').set_index('item')

    table_name = 'TUMOR'
    description = '''
    tumor stuff...@@@
    '''

    def domains(self):
        return pd.Series(dict(
            TABLE_NAME=self.table_name,
            DOMAIN_DESCRIPTION=self.description,
            DOMAIN_ORDER=-1,
        ))

    # TODO: RELATIONAL: table_name, relation, integrity details, order
    # TODO: constraints

    def fields(self, data_raw):
        
        fields = pd.DataFrame({
            'TABLE_NAME': self.table_name,
            'FIELD_NAME': self.field_info.name,       # ISSUE: UPPER_SNAKE_CASE?
            'RDBMS_DATA_TYPE': self.field_info.type,  # ISSUE@@@
            'SAS_DATA_TYPE': self.field_info.type,    # ISSUE
            'DATA_FORMAT': self.field_info.type,      # ISSUE
            'REPLICATED_FIELD': 'NO',
            'UNIT_OF_MEASURE': '',  # ISSUE
            'FIELD_DEFINITION': 'TODO',
        })
        fields['FIELD_ORDER'] = range(len(fields))
        fields = fields.set_index(['TABLE_NAME', 'FIELD_NAME']).sort_values('FIELD_ORDER')
        vals = self.valuesets(data_raw)
        vals = vals.groupby(['TABLE_NAME', 'FIELD_NAME'])
        fields['VALUESET'] = vals.VALUESET_ITEM.apply(';'.join)
        fields['VALUESET_DESCRIPTOR'] = vals.VALUESET_ITEM_DESCRIPTOR.apply(';'.join)
        return fields

    def valuesets(self, data_raw):
        found = []
        for info in (data_raw / 'code-labels').glob('*.csv'):
            skiprows = 0
            if info.open().readline().startswith('#'):
                skiprows = 1
            codes = pd.read_csv(info, skiprows=skiprows,
                                na_filter=False,
                                dtype={'code': str, 'label': str})
            if 'code' not in codes.columns or 'label' not in codes.columns:
                raise ValueError((info, codes.columns))
            codes['TABLE_NAME'] = self.table_name
            codes['FIELD_NAME'] = info.stem
            codes['VALUESET_ITEM'] = codes.code
            codes['VALUESET_ITEM_DESCRIPTOR'] = codes.code + '=' + codes.label
            found.append(codes)
        return pd.concat(found)

    converters = pd.Series({
        'factor': noop,  # category?
        'character': noop,
        'facility': noop,
        'city': noop,
        'county': noop,
        'postal': noop,
        'census_tract': noop,
        'boolean01': lambda ch: not not int(ch),
        'age': int,
        'Date': from_date,
        'census_block': noop,
        'sentineled_integer': noop,
        'count': int,
        'integer': int,
        'boolean12': lambda ch: ch == '2',
        'icd_code': noop,
        'override': noop,
        'ssn': noop,
        'address': noop,
        'numeric': float,
        'telephone': noop,
        'physician': noop,
        'icd_9': noop,
        'sentineled_numeric': noop,
        'datetime': noop,
    })
    
    def ncdb(self, fp,
             items=[390, 400, 410, 380, 560],
             nrows=20):
        field = self.items.loc[items].merge(
            self.field_info.loc[items],
            left_index=True, right_index=True)
        print(field)
        converters = { ix: self.converters[f.type]
                       for ix, (_, f) in enumerate(field.iterrows()) }

        return pd.read_fwf(fp,
                           header=None,
                           memory_map=True,
                           nrows=nrows,
                           colspecs=list(zip(field.start_col, field.end_col + 1)),
                           converters=converters,
                           names=field.name)

v18 = RecordFormat(_raw_data())

# print(v18.items.head())

# v18.ncdb(tr_file.open())

# x = v18.valuesets(_raw_data())
# x[x.FIELD_NAME == 'laterality']

v18.fields(_raw_data()).head(40)

# %% [markdown]
# ## Use Case: GPC Breast Cancer Survey
#
# The NAACCR format has 500+ items. To provide initial focus, let's use the variables from the 2016 GPC breast cancer survey:

# %%
from pyspark import SparkFiles

spark.sparkContext.addFile('https://raw.githubusercontent.com/kumc-bmi/bc_qa/rc_codebook/bc-variable.csv')
bc_var = spark.read.csv(SparkFiles.get('bc-variable.csv'), header=True)
bc_var.createOrReplaceTempView("bc_var")

x = spark.sql('''
select *
from bc_var
'''
)

x.limit(5).toPandas()

# %% [markdown]
# Among all that i2b2 metadata, what we need is the NAACCR item numbers:

# %%
bc_item = spark.sql(r'''
select distinct item from (
  select cast(regexp_extract(concept_path, '\\\\i2b2\\\\naaccr\\\\S:[^\\\\]+\\\\(\\d+)', 1) as int) as item 
  from bc_var
)
where item is not null
order by item
'''
)
bc_item.createOrReplaceTempView("bc_item")

bc_item.limit(5).toPandas()