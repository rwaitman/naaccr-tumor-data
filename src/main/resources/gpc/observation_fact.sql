CREATE TABLE OBSERVATION_FACT (
	ENCOUNTER_NUM   	NUMBER(38,0) NOT NULL,
	PATIENT_NUM     	NUMBER(38,0) NOT NULL,
	CONCEPT_CD      	VARCHAR2(50) NOT NULL,
	PROVIDER_ID     	VARCHAR2(50) NOT NULL,
	START_DATE      	DATE NOT NULL,
	MODIFIER_CD     	VARCHAR2(100) default '@' NOT NULL,
	INSTANCE_NUM	    NUMBER(18,0) default '1' NOT NULL,
	VALTYPE_CD      	VARCHAR2(50) NULL,
	TVAL_CHAR       	VARCHAR2(255) NULL,
	NVAL_NUM        	NUMBER(18,5) NULL,
	VALUEFLAG_CD    	VARCHAR2(50) NULL,
	QUANTITY_NUM    	NUMBER(18,5) NULL,
	UNITS_CD        	VARCHAR2(50) NULL,
	END_DATE        	DATE NULL,
	LOCATION_CD     	VARCHAR2(50) NULL,
	OBSERVATION_BLOB	CLOB NULL,
	CONFIDENCE_NUM  	NUMBER(18,5) NULL,
	UPDATE_DATE     	DATE NULL,
	DOWNLOAD_DATE   	DATE NULL,
	IMPORT_DATE     	DATE NULL,
	SOURCESYSTEM_CD 	VARCHAR2(50) NULL,
	UPLOAD_ID       	NUMBER(38,0) NULL,
    CONSTRAINT OBSERVATION_FACT_PK PRIMARY KEY(PATIENT_NUM, CONCEPT_CD,  MODIFIER_CD, START_DATE, ENCOUNTER_NUM, INSTANCE_NUM, PROVIDER_ID)
)
;
