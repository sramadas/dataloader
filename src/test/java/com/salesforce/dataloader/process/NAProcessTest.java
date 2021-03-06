/*
 * Copyright (c) 2012, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided
 * that the following conditions are met:
 *
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *    following disclaimer.
 *
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
 *    the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or
 *    promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.dataloader.process;

import java.io.File;
import java.util.*;

import junit.framework.TestSuite;

import com.salesforce.dataloader.ConfigGenerator;
import com.salesforce.dataloader.ConfigTestSuite;
import com.salesforce.dataloader.action.OperationInfo;
import com.salesforce.dataloader.config.Config;
import com.salesforce.dataloader.controller.Controller;
import com.salesforce.dataloader.dao.csv.CSVFileReader;
import com.salesforce.dataloader.dao.csv.CSVFileWriter;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;

/**
 * This class test that #N/A can be used to set fields to null when Use Bulk Api is enabled.
 * 
 * @author Jeff Lai
 * @since 25.0
 */
public class NAProcessTest extends ProcessTestBase {

    private final String TASK_SUBJECT = "NATest";
    private final String TARGET_DIR = getProperty("target.dir").trim();
    private final String CSV_DIR_PATH = TARGET_DIR + File.separator + getClass().getSimpleName();
    private final String CSV_FILE_PATH = CSV_DIR_PATH + File.separator + "na.csv";
    private String userId = null;

    public NAProcessTest(String name, Map<String, String> config) {
        super(name, config);
    }

    public NAProcessTest(String name) {
        super(name);
    }

    public static TestSuite suite() {
        return ConfigTestSuite.createSuite(NAProcessTest.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (userId == null) userId = getUserId();
    }

    public static ConfigGenerator getConfigGenerator() {
        final ConfigGenerator bulkApiTrue = new ConfigSettingGenerator(ProcessTestBase.getConfigGenerator(),
                Config.BULK_API_ENABLED, Boolean.TRUE.toString());
        final ConfigGenerator bulkApiFalse = new ConfigSettingGenerator(ProcessTestBase.getConfigGenerator(),
                Config.BULK_API_ENABLED, Boolean.FALSE.toString());
        return new UnionConfigGenerator(bulkApiTrue, bulkApiFalse);
    }

    public void testTextFieldInsert() throws Exception {
        runNAtest("Description", false, OperationInfo.insert);
    }

    public void testTextFieldUpdate() throws Exception {
        runNAtest("Description", false, OperationInfo.update);
    }

    public void testDateTimeFieldInsert() throws Exception {
        runNAtest("ReminderDateTime", true, OperationInfo.insert);
    }

    public void testDateTimeFieldUpdate() throws Exception {
        runNAtest("ReminderDateTime", true, OperationInfo.update);
    }
    
    public void testDateFieldInsert() throws Exception {
        runNAtest("ActivityDate", true, OperationInfo.insert);
    }

    public void testDateFieldUpdate() throws Exception {
        runNAtest("ActivityDate", true, OperationInfo.update);
    }

    protected void runNAtest(String nullFieldName, boolean isDateField, OperationInfo operation) throws Exception {
        String taskId = null;
        if (!operation.equals(OperationInfo.insert)) taskId = createTask(nullFieldName, isDateField);
        generateCsv(nullFieldName, taskId);
        Map<String, String> argMap = getTestConfig(operation, CSV_FILE_PATH, getTestDataDir() + File.separator
                + "NAProcessTest.sdl", false);
        argMap.put(Config.ENTITY, "Task");
        argMap.remove(Config.EXTERNAL_ID_FIELD);
        Controller controller = null;
        if (!getController().getConfig().getBoolean(Config.BULK_API_ENABLED) && isDateField) {
            controller = runProcess(argMap, true, null, 0, 0, 1, false);
            String errorFile = controller.getConfig().getStringRequired(Config.OUTPUT_ERROR);
            String errorMessage = getCsvFieldValue(errorFile, "ERROR");
            assertEquals("unexpected error message",
                    "Error converting value to correct data type: Failed to parse date: #N/A", errorMessage);
        } else {
            int numInsert = operation.equals(OperationInfo.insert) ? 1 : 0;
            int numUpdate = operation.equals(OperationInfo.update) ? 1 : 0;
            controller = runProcess(argMap, true, null, numInsert, numUpdate, 0, false);
            String successFile = controller.getConfig().getStringRequired(Config.OUTPUT_SUCCESS);
            taskId = getCsvFieldValue(successFile, "ID");
            String expectedNullFieldValue = getController().getConfig().getBoolean(Config.BULK_API_ENABLED) ? null
                    : "#N/A";
            QueryResult result = getController().getPartnerClient().query(
                    "select " + nullFieldName + " from Task where Id='" + taskId + "'");
            assertEquals(1, result.getSize());
            String actualNullFieldValue = (String)result.getRecords()[0].getField(nullFieldName);
            assertEquals("unexpected field value", expectedNullFieldValue, actualNullFieldValue);
        }
    }

    private String createTask(String fieldToNullName, boolean isDateField) throws Exception {
        Object fieldToNullValue = isDateField ? new Date() : "asdf";
        SObject task = new SObject();
        task.setType("Task");
        task.setField("OwnerId", userId);
        task.setField("Subject", TASK_SUBJECT);
        task.setField(fieldToNullName, fieldToNullValue);
        SaveResult[] result = getController().getPartnerClient().getClient().create(new SObject[] { task });
        assertEquals(1, result.length);
        if (!result[0].getSuccess())
            fail("creation of task failed with error " + result[0].getErrors()[0].getMessage());
        return result[0].getId();
    }

    private String getCsvFieldValue(String csvFile, String fieldName) throws Exception {
        CSVFileReader reader = new CSVFileReader(csvFile);
        reader.open();
        assertEquals(1, reader.getTotalRows());
        String fieldValue = (String)reader.readRow().get(fieldName);
        reader.close();
        return fieldValue;
    }

    private String getUserId() throws Exception {
        QueryResult result = getController().getPartnerClient().query(
                "select id from user where username='" + getController().getConfig().getString(Config.USERNAME) + "'");
        assertEquals(1, result.getSize());
        return result.getRecords()[0].getId();
    }

    /**
     * We have to generate the csv file because the user id will change.
     */
    protected void generateCsv(String nullFieldName, String id) throws Exception {
        File csvDir = new File(CSV_DIR_PATH);
        if (!csvDir.exists()) csvDir.mkdirs();
        File csvFile = new File(CSV_FILE_PATH);
        if (csvFile.exists()) csvFile.delete();

        Map<String, Object> map = new HashMap<String, Object>();
        map.put("OwnerId", userId);
        map.put("Subject", TASK_SUBJECT);
        map.put(nullFieldName, "#N/A");
        if (id != null) map.put("Id", id);

        CSVFileWriter writer = null;
        try {
            writer = new CSVFileWriter(CSV_FILE_PATH, DEFAULT_CHARSET);
            writer.open();
            writer.setColumnNames(new ArrayList<String>(map.keySet()));
            writer.writeRow(map);
        } finally {
            if (writer != null) writer.close();
        }
    }

    @Override
    protected void cleanRecords() {
        deleteSfdcRecords("Task", "Subject='" + TASK_SUBJECT + "'", 0);
    }

}
