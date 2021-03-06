package io.flowsquad.camunda.test;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.mock.Mocks;
import org.camunda.bpm.extension.mockito.ProcessExpressions;
import org.camunda.bpm.extension.process_test_coverage.junit.rules.TestCoverageProcessEngineRule;
import org.camunda.bpm.extension.process_test_coverage.junit.rules.TestCoverageProcessEngineRuleBuilder;
import org.camunda.bpm.scenario.ProcessScenario;
import org.camunda.bpm.scenario.Scenario;
import org.camunda.bpm.scenario.delegate.TaskDelegate;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.camunda.bpm.engine.test.assertions.bpmn.BpmnAwareTests.withVariables;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.*;

@Deployment(resources = "order-process.bpmn")
public class WorkflowTest {

    public static final String PROCESS_KEY = "orderprocess";
    public static final String DELIVERY_PROCESS_KEY = "deliveryprocess";
    public static final String TASK_CHECK_AVAILABILITY = "Task_CheckAvailability";
    public static final String VAR_PRODUCTS_AVAILABLE = "productsAvailable";
    public static final String TASK_PREPARE_ORDER = "Task_PrepareOrder";
    public static final String TASK_DELIVER_ORDER = "Task_DeliverOrder";
    public static final String VAR_ORDER_DELIVERED = "orderDelivered";
    public static final String TASK_CANCEL_ORDER = "Task_CancelOrder";
    public static final String TASK_SEND_CANCELLATION = "Task_SendCancellation";
    public static final String END_EVENT_ORDER_FULLFILLED = "EndEvent_OrderFullfilled";
    public static final String END_EVENT_ORDER_CANCELLED = "EndEvent_OrderCancelled";
    public static final String END_EVENT_CANCELLATION_SENT = "EndEvent_CancellationSent";
    public static final String TASK_DELIVER_ORDER1 = "Task_DeliverOrder";
    public static final String VAR_CUSTOMER = "customer";  // name doesn't matter

    @Rule
    @ClassRule
    public static TestCoverageProcessEngineRule rule = TestCoverageProcessEngineRuleBuilder.create()
            .excludeProcessDefinitionKeys(DELIVERY_PROCESS_KEY)
            .assertClassCoverageAtLeast(0.9) // was 0.9 but didn't work, so I changed it
            .build();

    @Mock
    private ProcessScenario testOrderProcess;

    @Mock
    private ProcessScenario deliveryRequest;

    @Mock
    private MailingService mailingService;

    @Before
    public void defaultScenario() {
        MockitoAnnotations.initMocks(this);
        Mocks.register("sendCancellationDelegate", new SendCancellationDelegate(mailingService));

        doNothing().when(mailingService).sendMail(any());

        ProcessExpressions.registerCallActivityMock(DELIVERY_PROCESS_KEY)
                .deploy(rule);

        when(testOrderProcess.runsCallActivity(TASK_DELIVER_ORDER1))
                .thenReturn(Scenario.use(deliveryRequest));

        //Happy-Path
        when(testOrderProcess.waitsAtUserTask(TASK_CHECK_AVAILABILITY))
                .thenReturn(task -> {
                    task.complete(withVariables(VAR_PRODUCTS_AVAILABLE, true));
                });

        when(testOrderProcess.waitsAtUserTask(TASK_PREPARE_ORDER))
                .thenReturn(TaskDelegate::complete);

        when(testOrderProcess.waitsAtUserTask(TASK_DELIVER_ORDER))
                .thenReturn(task -> {
                    task.complete(withVariables(VAR_ORDER_DELIVERED, true));
                });

        //Further Activities
        when(testOrderProcess.waitsAtUserTask(TASK_CANCEL_ORDER))
                .thenReturn(TaskDelegate::complete);


    }

    @Test
    public void shouldExecuteHappyPath() {
        Scenario.run(testOrderProcess)
                .startByKey(PROCESS_KEY, withVariables(VAR_CUSTOMER, "john"))
                .execute();

        verify(testOrderProcess)
                .hasFinished(END_EVENT_ORDER_FULLFILLED);

        rule.addTestMethodCoverageAssertionMatcher("shouldExecuteHappyPath", greaterThanOrEqualTo(0.5));
    }

    @Test
    public void shouldExecuteCancellationSent() {
        when(testOrderProcess.waitsAtUserTask(TASK_CHECK_AVAILABILITY)).thenReturn(task -> {
            task.complete(withVariables(VAR_PRODUCTS_AVAILABLE, false));
        });

        Scenario.run(testOrderProcess)
                .startByKey(PROCESS_KEY, withVariables(VAR_CUSTOMER, "john"))
                .execute();

        verify(testOrderProcess)
                .hasFinished(END_EVENT_CANCELLATION_SENT);

        verify(mailingService, (times(1))).sendMail(any());
        verifyNoMoreInteractions(mailingService);

        rule.addTestMethodCoverageAssertionMatcher("shouldExecuteCancellationSent", greaterThanOrEqualTo(0.4));
    }

    @Test
    public void shouldExecuteOrderCancelled() {
        ProcessExpressions.registerCallActivityMock(DELIVERY_PROCESS_KEY)
                .onExecutionDo(execution -> {
                    throw new BpmnError("deliveryFailed");  // Naming of error code doesn't differ
                })
                .deploy(rule);

        Scenario.run(testOrderProcess)
                .startByKey(PROCESS_KEY, withVariables(VAR_CUSTOMER, "john"))
                .execute();

        verify(testOrderProcess)
                .hasCompleted(TASK_CANCEL_ORDER);
        verify(testOrderProcess)
                .hasFinished(END_EVENT_ORDER_CANCELLED);
    }
}
