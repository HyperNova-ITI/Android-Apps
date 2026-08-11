package com.hypernova.contracts.phone;

import com.hypernova.contracts.phone.PhoneResult;

oneway interface IPhoneCommandCallback {
    void onResult(in PhoneResult result);
}
