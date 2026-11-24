package com.jobpilot.web;

final class Web {

    private Web() {
    }

    /** Only allow redirects back inside this app. */
    static String safeBack(String back) {
        return back != null && back.startsWith("/") && !back.startsWith("//") ? back : "/";
    }
}
