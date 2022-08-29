package eu.siacs.conversations.xmpp.jingle;

public enum RtpEndUserState {
    INCOMING_CALL, //received a 'propose' message
    CONNECTING, //session-initiate or session-accepted but no webrtc peer connection yet
    CONNECTED, //session-accepted and webrtc peer connection is connected
    RECONNECTING, //session-accepted and webrtc peer connection was connected once but is currently disconnected or failed
    FINDING_DEVICE, //'propose' has been sent out; no 184 ack yet
    RINGING, //'propose' has been sent out and it has been 184 acked
    ACCEPTING_CALL, //'proceed' message has been sent; but no session-initiate has been received
    ENDING_CALL, //libwebrt says 'closed' but session-terminate hasnt gone through
    ENDED, //close UI
    DECLINED_OR_BUSY, //other party declined; no retry button
    CONNECTIVITY_ERROR, //network error; retry button
    CONNECTIVITY_LOST_ERROR, //network error but for call duration > 0
    RETRACTED, //user pressed home or power button during 'ringing' - shows retry button
    APPLICATION_ERROR, //something rather bad happened; libwebrtc failed or we got in IQ-error
    SECURITY_ERROR //problem with DTLS (missing) or verification
}
