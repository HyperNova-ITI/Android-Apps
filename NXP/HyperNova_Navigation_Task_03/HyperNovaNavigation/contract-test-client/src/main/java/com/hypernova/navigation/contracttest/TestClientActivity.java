package com.hypernova.navigation.contracttest;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hypernova.contracts.HyperNovaContract;
import com.hypernova.contracts.navigation.INavigationCommandCallback;
import com.hypernova.contracts.navigation.INavigationCommandService;
import com.hypernova.contracts.navigation.NavigationContract;
import com.hypernova.contracts.navigation.NavigationDestination;
import com.hypernova.contracts.navigation.NavigationResult;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

public final class TestClientActivity extends Activity {

    private static final String TAG = "NavContractTest";

    private INavigationCommandService navigationService;

    private boolean bound = false;
    private boolean bindRequested = false;

    private TextView connectionText;
    private TextView apiVersionText;
    private TextView lastActionText;
    private TextView lastCallbackText;
    private TextView logText;

    private EditText queryInput;

    private ScrollView logScrollView;

    private String firstDestinationId;

    private final INavigationCommandCallback callback =
            new INavigationCommandCallback.Stub() {

                @Override
                public void onResult(NavigationResult result) {
                    runOnUiThread(
                            () -> handleResult(result)
                    );
                }
            };

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {

                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder service
                ) {
                    navigationService =
                            INavigationCommandService.Stub.asInterface(service);

                    bound = true;
                    bindRequested = false;

                    connectionText.setText(
                            "CONNECTED"
                    );

                    appendLog(
                            "BINDER CONNECTED\n" +
                            name.flattenToShortString()
                    );

                    showToast(
                            "Navigation service connected"
                    );

                    testApiVersion();
                }

                @Override
                public void onServiceDisconnected(
                        ComponentName name
                ) {
                    navigationService = null;
                    bound = false;
                    bindRequested = false;

                    connectionText.setText(
                            "DISCONNECTED"
                    );

                    appendLog(
                            "SERVICE DISCONNECTED\n" +
                            name.flattenToShortString()
                    );
                }

                @Override
                public void onBindingDied(
                        ComponentName name
                ) {
                    navigationService = null;
                    bound = false;
                    bindRequested = false;

                    connectionText.setText(
                            "BINDING DIED"
                    );

                    appendLog(
                            "BINDING DIED\n" +
                            name.flattenToShortString()
                    );
                }

                @Override
                public void onNullBinding(
                        ComponentName name
                ) {
                    navigationService = null;
                    bound = false;
                    bindRequested = false;

                    connectionText.setText(
                            "NULL BINDING"
                    );

                    appendLog(
                            "NULL BINDING\n" +
                            name.flattenToShortString()
                    );
                }
            };

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        buildUi();

        appendLog(
                "Navigation Contract Test Client started."
        );

        appendLog(
                "Expected API version = " +
                HyperNovaContract.API_VERSION
        );

        appendLog(
                "Target package = " +
                NavigationContract.PACKAGE_NAME
        );

        appendLog(
                "Target service = " +
                NavigationContract.COMMAND_SERVICE
        );
    }

    @Override
    protected void onStart() {
        super.onStart();

        bindNavigationService();
    }

    @Override
    protected void onStop() {
        if (bound || bindRequested) {
            try {
                unbindService(
                        serviceConnection
                );
            } catch (IllegalArgumentException ignored) {
                // Service was already unbound.
            }
        }

        navigationService = null;
        bound = false;
        bindRequested = false;

        super.onStop();
    }

    private void buildUi() {
        ScrollView pageScroll =
                new ScrollView(this);

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setPadding(
                dp(24),
                dp(24),
                dp(24),
                dp(40)
        );

        pageScroll.addView(root);

        TextView title =
                new TextView(this);

        title.setText(
                "HyperNova Navigation\nAIDL Contract Test"
        );

        title.setTextSize(25);

        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        root.addView(title);

        connectionText =
                createStatusText(
                        "CONNECTION",
                        "DISCONNECTED"
                );

        root.addView(
                connectionText
        );

        apiVersionText =
                createStatusText(
                        "API VERSION",
                        "NOT TESTED"
                );

        root.addView(
                apiVersionText
        );

        lastActionText =
                createStatusText(
                        "LAST ACTION",
                        "NONE"
                );

        root.addView(
                lastActionText
        );

        lastCallbackText =
                createStatusText(
                        "LAST CALLBACK",
                        "NONE"
                );

        root.addView(
                lastCallbackText
        );

        queryInput =
                new EditText(this);

        queryInput.setHint(
                "Search query"
        );

        queryInput.setText(
                "Valeo"
        );

        queryInput.setSingleLine(
                true
        );

        LinearLayout.LayoutParams queryParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        queryParams.topMargin =
                dp(18);

        root.addView(
                queryInput,
                queryParams
        );

        root.addView(
                createButton(
                        "1. BIND / REBIND SERVICE",
                        view -> {
                            markAction(
                                    "BIND SERVICE"
                            );

                            bindNavigationService();
                        }
                )
        );

        root.addView(
                createButton(
                        "2. GET API VERSION",
                        view -> {
                            markAction(
                                    "GET API VERSION"
                            );

                            testApiVersion();
                        }
                )
        );

        root.addView(
                createButton(
                        "3. SEARCH DESTINATIONS",
                        view -> {
                            markAction(
                                    "SEARCH DESTINATIONS"
                            );

                            searchDestinations();
                        }
                )
        );

        root.addView(
                createButton(
                        "4. GET SAVED DESTINATIONS",
                        view -> {
                            markAction(
                                    "GET SAVED DESTINATIONS"
                            );

                            getSavedDestinations();
                        }
                )
        );

        root.addView(
                createButton(
                        "5. NAVIGATE TO FIRST SEARCH RESULT",
                        view -> {
                            markAction(
                                    "SET DESTINATION"
                            );

                            navigateToFirstResult();
                        }
                )
        );

        root.addView(
                createButton(
                        "6. CANCEL NAVIGATION",
                        view -> {
                            markAction(
                                    "CANCEL NAVIGATION"
                            );

                            cancelNavigation();
                        }
                )
        );

        root.addView(
                createButton(
                        "7. OPEN NAVIGATION UI",
                        view -> {
                            markAction(
                                    "OPEN NAVIGATION UI"
                            );

                            openNavigationUi();
                        }
                )
        );

        root.addView(
                createButton(
                        "CLEAR LOG",
                        view -> {
                            logText.setText("");

                            appendLog(
                                    "Log cleared."
                            );
                        }
                )
        );

        TextView logTitle =
                new TextView(this);

        logTitle.setText(
                "AIDL CALLBACK LOG"
        );

        logTitle.setTextSize(
                17
        );

        logTitle.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        logTitle.setPadding(
                0,
                dp(24),
                0,
                dp(8)
        );

        root.addView(
                logTitle
        );

        logScrollView =
                new ScrollView(this);

        logScrollView.setFillViewport(
                true
        );

        logText =
                new TextView(this);

        logText.setTextSize(
                14
        );

        logText.setTypeface(
                Typeface.MONOSPACE
        );

        logText.setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
        );

        logScrollView.addView(
                logText
        );

        LinearLayout.LayoutParams logParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(520)
                );

        logParams.topMargin =
                dp(4);

        root.addView(
                logScrollView,
                logParams
        );

        setContentView(
                pageScroll
        );
    }

    private TextView createStatusText(
            String label,
            String value
    ) {
        TextView textView =
                new TextView(this);

        textView.setText(
                label +
                ": " +
                value
        );

        textView.setTextSize(
                17
        );

        textView.setPadding(
                0,
                dp(8),
                0,
                dp(8)
        );

        return textView;
    }

    private Button createButton(
            String text,
            View.OnClickListener listener
    ) {
        Button button =
                new Button(this);

        button.setText(
                text
        );

        button.setAllCaps(
                false
        );

        button.setOnClickListener(
                listener
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.topMargin =
                dp(8);

        button.setLayoutParams(
                params
        );

        return button;
    }

    private void markAction(
            String action
    ) {
        lastActionText.setText(
                "LAST ACTION: " +
                action
        );

        appendLog(
                "CLICKED: " +
                action
        );

        showToast(
                action
        );
    }

    private void bindNavigationService() {
        if (
                bound &&
                navigationService != null
        ) {
            appendLog(
                    "Already connected to Navigation service."
            );

            connectionText.setText(
                    "CONNECTION: CONNECTED"
            );

            return;
        }

        if (bindRequested) {
            appendLog(
                    "Bind request already pending."
            );

            return;
        }

        Intent intent =
                new Intent(
                        NavigationContract.BIND_COMMAND_ACTION
                );

        intent.setComponent(
                new ComponentName(
                        NavigationContract.PACKAGE_NAME,
                        NavigationContract.COMMAND_SERVICE
                )
        );

        appendLog(
                "Binding using action:\n" +
                NavigationContract.BIND_COMMAND_ACTION
        );

        try {
            bindRequested = true;

            boolean accepted =
                    bindService(
                            intent,
                            serviceConnection,
                            Context.BIND_AUTO_CREATE
                    );

            appendLog(
                    "bindService() returned = " +
                    accepted
            );

            if (!accepted) {
                bindRequested = false;

                connectionText.setText(
                        "CONNECTION: BIND REJECTED"
                );
            }

        } catch (SecurityException exception) {
            bindRequested = false;

            connectionText.setText(
                    "CONNECTION: PERMISSION DENIED"
            );

            appendLog(
                    "SecurityException while binding:\n" +
                    exception
            );
        }
    }

    private void testApiVersion() {
        INavigationCommandService service =
                navigationService;

        if (service == null) {
            apiVersionText.setText(
                    "API VERSION: SERVICE NOT CONNECTED"
            );

            appendLog(
                    "getApiVersion skipped: service not connected."
            );

            return;
        }

        try {
            int version =
                    service.getApiVersion();

            if (
                    version ==
                    HyperNovaContract.API_VERSION
            ) {
                apiVersionText.setText(
                        "API VERSION: " +
                        version +
                        " - PASS"
                );

                appendLog(
                        "getApiVersion() = " +
                        version +
                        "\nAPI VERSION TEST: PASS"
                );

            } else {
                apiVersionText.setText(
                        "API VERSION: " +
                        version +
                        " - FAIL"
                );

                appendLog(
                        "API VERSION TEST: FAIL\n" +
                        "Expected = " +
                        HyperNovaContract.API_VERSION +
                        "\nActual = " +
                        version
                );
            }

        } catch (RemoteException exception) {
            apiVersionText.setText(
                    "API VERSION: REMOTE ERROR"
            );

            appendLog(
                    "getApiVersion RemoteException:\n" +
                    exception
            );
        }
    }

    private void searchDestinations() {
        INavigationCommandService service =
                navigationService;

        if (service == null) {
            appendLog(
                    "Search skipped: service not connected."
            );

            return;
        }

        String query =
                queryInput
                        .getText()
                        .toString()
                        .trim();

        if (query.isEmpty()) {
            appendLog(
                    "Search skipped: query is blank."
            );

            return;
        }

        firstDestinationId = null;

        String requestId =
                newRequestId(
                        "search"
                );

        appendLog(
                "SEARCH REQUEST\n" +
                "requestId = " +
                requestId +
                "\nquery = " +
                query
        );

        try {
            service.searchDestinations(
                    requestId,
                    query,
                    callback
            );

        } catch (RemoteException exception) {
            appendLog(
                    "searchDestinations RemoteException:\n" +
                    exception
            );
        }
    }

    private void getSavedDestinations() {
        INavigationCommandService service =
                navigationService;

        if (service == null) {
            appendLog(
                    "Saved destinations skipped: service not connected."
            );

            return;
        }

        String requestId =
                newRequestId(
                        "saved"
                );

        appendLog(
                "GET SAVED REQUEST\n" +
                "requestId = " +
                requestId
        );

        try {
            service.getSavedDestinations(
                    requestId,
                    callback
            );

        } catch (RemoteException exception) {
            appendLog(
                    "getSavedDestinations RemoteException:\n" +
                    exception
            );
        }
    }

    private void navigateToFirstResult() {
        INavigationCommandService service =
                navigationService;

        if (service == null) {
            appendLog(
                    "setDestination skipped: service not connected."
            );

            return;
        }

        if (
                firstDestinationId == null ||
                firstDestinationId.isEmpty()
        ) {
            appendLog(
                    "No destination ID stored yet.\n" +
                    "Run Search Destinations first and wait for CONFIRMED."
            );

            showToast(
                    "Search first"
            );

            return;
        }

        String requestId =
                newRequestId(
                        "route"
                );

        appendLog(
                "SET DESTINATION REQUEST\n" +
                "requestId = " +
                requestId +
                "\ndestinationId = " +
                firstDestinationId
        );

        try {
            service.setDestination(
                    requestId,
                    firstDestinationId,
                    callback
            );

        } catch (RemoteException exception) {
            appendLog(
                    "setDestination RemoteException:\n" +
                    exception
            );
        }
    }

    private void cancelNavigation() {
        INavigationCommandService service =
                navigationService;

        if (service == null) {
            appendLog(
                    "Cancel skipped: service not connected."
            );

            return;
        }

        String requestId =
                newRequestId(
                        "cancel"
                );

        appendLog(
                "CANCEL REQUEST\n" +
                "requestId = " +
                requestId
        );

        try {
            service.cancelNavigation(
                    requestId,
                    callback
            );

        } catch (RemoteException exception) {
            appendLog(
                    "cancelNavigation RemoteException:\n" +
                    exception
            );
        }
    }

    private void handleResult(
            NavigationResult result
    ) {
        if (result == null) {
            lastCallbackText.setText(
                    "LAST CALLBACK: NULL RESULT"
            );

            appendLog(
                    "Callback returned null result."
            );

            return;
        }

        String status =
                statusName(
                        result.getStatus()
                );

        String state =
                navigationStateName(
                        result.getNavigationState()
                );

        lastCallbackText.setText(
                "LAST CALLBACK: " +
                result.getOperation() +
                " / " +
                status +
                " / " +
                state
        );

        StringBuilder text =
                new StringBuilder();

        text.append("CALLBACK")
                .append("\nrequestId = ")
                .append(
                        result.getRequestId()
                )

                .append("\noperation = ")
                .append(
                        result.getOperation()
                )

                .append("\nstatus = ")
                .append(status)

                .append(" (")
                .append(
                        result.getStatus()
                )
                .append(")")

                .append("\nmessage = ")
                .append(
                        result.getMessage()
                )

                .append("\nerrorCode = ")
                .append(
                        result.getErrorCode()
                )

                .append("\nnavigationState = ")
                .append(state)

                .append(" (")
                .append(
                        result.getNavigationState()
                )
                .append(")")

                .append("\netaSeconds = ")
                .append(
                        result.getEtaSeconds()
                )

                .append("\ndistanceMeters = ")
                .append(
                        result.getDistanceMeters()
                );

        NavigationDestination selected =
                result.getSelectedDestination();

        if (selected != null) {
            text.append(
                    "\nselectedDestination = "
            ).append(
                    describeDestination(
                            selected
                    )
            );
        }

        List<NavigationDestination> destinations =
                result.getDestinations();

        if (
                destinations != null &&
                !destinations.isEmpty()
        ) {
            text.append(
                    "\ndestinationCount = "
            ).append(
                    destinations.size()
            );

            for (
                    int index = 0;
                    index < destinations.size();
                    index++
            ) {
                NavigationDestination destination =
                        destinations.get(index);

                text.append("\n[")
                        .append(index)
                        .append("] ")
                        .append(
                                describeDestination(
                                        destination
                                )
                        );
            }

            if (
                    result.getStatus() ==
                    HyperNovaContract.STATUS_CONFIRMED
            ) {
                String id =
                        readDestinationId(
                                destinations.get(0)
                        );

                if (
                        id != null &&
                        !id.isEmpty()
                ) {
                    firstDestinationId =
                            id;

                    text.append(
                            "\nFIRST RESULT STORED FOR ROUTING = "
                    ).append(id);
                }
            }
        }

        appendLog(
                text.toString()
        );
    }

    private String readDestinationId(
            NavigationDestination destination
    ) {
        Object value =
                invokeGetter(
                        destination,
                        "getId"
                );

        return value == null
                ? null
                : String.valueOf(
                        value
                );
    }

    private String describeDestination(
            NavigationDestination destination
    ) {
        if (destination == null) {
            return "null";
        }

        StringBuilder text =
                new StringBuilder();

        appendProperty(
                text,
                "id",
                invokeGetter(
                        destination,
                        "getId"
                )
        );

        appendProperty(
                text,
                "title",
                invokeGetter(
                        destination,
                        "getTitle"
                )
        );

        appendProperty(
                text,
                "subtitle",
                invokeGetter(
                        destination,
                        "getSubtitle"
                )
        );

        appendProperty(
                text,
                "category",
                invokeGetter(
                        destination,
                        "getCategory"
                )
        );

        appendProperty(
                text,
                "distanceMeters",
                invokeGetter(
                        destination,
                        "getDistanceMeters"
                )
        );

        return text.toString();
    }

    private Object invokeGetter(
            NavigationDestination destination,
            String methodName
    ) {
        try {
            Method method =
                    destination
                            .getClass()
                            .getMethod(
                                    methodName
                            );

            return method.invoke(
                    destination
            );

        } catch (Exception ignored) {
            return null;
        }
    }

    private void appendProperty(
            StringBuilder text,
            String name,
            Object value
    ) {
        if (value == null) {
            return;
        }

        if (text.length() > 0) {
            text.append(", ");
        }

        text.append(name)
                .append("=")
                .append(value);
    }

    private void openNavigationUi() {
        Intent intent =
                new Intent(
                        NavigationContract.OPEN_ACTION
                );

        intent.setPackage(
                NavigationContract.PACKAGE_NAME
        );

        try {
            startActivity(
                    intent
            );

        } catch (Exception exception) {
            appendLog(
                    "Failed to open Navigation UI:\n" +
                    exception
            );
        }
    }

    private String newRequestId(
            String operation
    ) {
        return "contract-test-" +
                operation +
                "-" +
                UUID.randomUUID();
    }

    private String statusName(
            int status
    ) {
        switch (status) {

            case HyperNovaContract.STATUS_ACCEPTED:
                return "ACCEPTED";

            case HyperNovaContract.STATUS_CONFIRMED:
                return "CONFIRMED";

            case HyperNovaContract.STATUS_REJECTED:
                return "REJECTED";

            case HyperNovaContract.STATUS_UNAVAILABLE:
                return "UNAVAILABLE";

            case HyperNovaContract.STATUS_TIMEOUT:
                return "TIMEOUT";

            case HyperNovaContract.STATUS_CANCELLED:
                return "CANCELLED";

            default:
                return "UNKNOWN";
        }
    }

    private String navigationStateName(
            int state
    ) {
        switch (state) {

            case NavigationContract.STATE_IDLE:
                return "IDLE";

            case NavigationContract.STATE_CALCULATING:
                return "CALCULATING";

            case NavigationContract.STATE_ACTIVE:
                return "ACTIVE";

            case NavigationContract.STATE_ARRIVED:
                return "ARRIVED";

            case NavigationContract.STATE_ERROR:
                return "ERROR";

            default:
                return "UNKNOWN";
        }
    }

    private void appendLog(
            String message
    ) {
        Log.d(
                TAG,
                message
        );

        if (logText == null) {
            return;
        }

        logText.append(
                message +
                "\n----------------------------------------\n"
        );

        if (logScrollView != null) {
            logScrollView.post(
                    () -> logScrollView.fullScroll(
                            View.FOCUS_DOWN
                    )
            );
        }
    }

    private void showToast(
            String message
    ) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value *
                getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
}
