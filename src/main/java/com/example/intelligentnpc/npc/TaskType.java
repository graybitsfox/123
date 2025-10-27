package com.example.intelligentnpc.npc;

public enum TaskType {
    BUILD("build", "строить"),
    TRADE("trade", "торговать"),
    GUARD("guard", "охранять"),
    FOLLOW("follow", "следовать"),
    STAY("stay", "стоять"),
    EXPLORE("explore", "исследовать"),
    REST("rest", "отдыхать"),
    LEARN("learn", "учиться");

    private final String name;
    private final String russianName;

    TaskType(String name, String russianName) {
        this.name = name;
        this.russianName = russianName;
    }

    public static TaskType fromString(String text) {
        for (TaskType b : TaskType.values()) {
            if (b.name.equalsIgnoreCase(text) || b.russianName.equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }
}
