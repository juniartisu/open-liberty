/*******************************************************************************
 * Copyright (c) 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.microprofile.openapi.validation.test;

import org.junit.Assert;
import org.junit.Test;

import com.ibm.ws.microprofile.openapi.impl.model.OpenAPIImpl;
import com.ibm.ws.microprofile.openapi.impl.model.responses.APIResponseImpl;
import com.ibm.ws.microprofile.openapi.impl.model.responses.APIResponsesImpl;
import com.ibm.ws.microprofile.openapi.impl.validation.ResponsesValidator;
import com.ibm.ws.microprofile.openapi.test.utils.TestValidationContextHelper;
import com.ibm.ws.microprofile.openapi.test.utils.TestValidationHelper;
import com.ibm.ws.microprofile.openapi.utils.OpenAPIModelWalker.Context;

/**
 *
 */
public class ResponsesValidatorTest {

    String key = null;
    OpenAPIImpl model = new OpenAPIImpl();
    Context context = new TestValidationContextHelper(model);

    @Test
    public void testCorrectResponses() {
        ResponsesValidator validator = ResponsesValidator.getInstance();
        TestValidationHelper vh = new TestValidationHelper();

        APIResponseImpl responseOne = new APIResponseImpl();
        responseOne.description("Successful Operation");

        APIResponseImpl responseTwo = new APIResponseImpl();
        responseTwo.description("Bad Request");

        APIResponseImpl responseThree = new APIResponseImpl();
        responseThree.description("Server Error");

        APIResponseImpl responseFour = new APIResponseImpl();
        responseFour.description("Default response");

        APIResponsesImpl responses = new APIResponsesImpl();
        responses.addApiResponse("200", responseOne);
        responses.addApiResponse("400", responseTwo);
        responses.addApiResponse("500", responseThree);
        responses.addApiResponse("default", responseFour);

        validator.validate(vh, context, key, responses);
        Assert.assertEquals(0, vh.getEventsSize());
    }

    @Test
    public void testEmptyResponses() {
        ResponsesValidator validator = ResponsesValidator.getInstance();
        TestValidationHelper vh = new TestValidationHelper();

        APIResponsesImpl responses = new APIResponsesImpl();

        validator.validate(vh, context, key, responses);
        Assert.assertEquals(1, vh.getEventsSize());
        Assert.assertTrue(vh.getResult().getEvents().get(0).message.contains("The Responses object must contain at least one response code"));
    }

    @Test
    public void testNullResponses() {
        ResponsesValidator validator = ResponsesValidator.getInstance();
        TestValidationHelper vh = new TestValidationHelper();

        APIResponsesImpl responses = null;

        validator.validate(vh, context, key, responses);
        Assert.assertEquals(0, vh.getEventsSize());
    }

    @Test
    public void testResponsesWithNoSuccess() {
        ResponsesValidator validator = ResponsesValidator.getInstance();
        TestValidationHelper vh = new TestValidationHelper();

        APIResponseImpl responseTwo = new APIResponseImpl();
        responseTwo.description("Bad Request");

        APIResponseImpl responseThree = new APIResponseImpl();
        responseThree.description("Server Error");

        APIResponsesImpl responses = new APIResponsesImpl();
        responses.addApiResponse("400", responseTwo);
        responses.addApiResponse("500", responseThree);

        validator.validate(vh, context, key, responses);
        Assert.assertEquals(1, vh.getEventsSize());
        Assert.assertTrue(vh.getResult().getEvents().get(0).message.contains("The Responses object should contain at least one response code for a successful operation"));
    }

}
