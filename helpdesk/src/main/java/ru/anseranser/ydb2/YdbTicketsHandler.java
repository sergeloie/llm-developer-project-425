package ru.anseranser.ydb2;

import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

import java.util.Arrays;

public class YdbTicketsHandler implements YcFunction<String, String> {

    private final EventDispatcher eventDispatcher;

    public YdbTicketsHandler() {
        eventDispatcher = new EventDispatcher();
    }


    @Override
    public String handle(String s, Context context) {
        try {
        DispatchResult dispatchResult = eventDispatcher.dispatchAndExtract(s);
        System.out.println("Source: " + s);
        System.out.println(dispatchResult);
        System.out.println(Arrays.toString(Action.values()));
        System.out.println(eventDispatcher.getClass());
        return "It works: " + s;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }
}
