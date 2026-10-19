package com.kingdomsai.core.action;

/** Resultado do pipeline. code é estável (para logs, testes e para a LLM entender a rejeição). */
public record ActionResult(boolean ok, String code, String message) {
    public static ActionResult ok(String message) {
        return new ActionResult(true, "ok", message);
    }

    public static ActionResult reject(String code, String message) {
        return new ActionResult(false, code, message);
    }

    @Override
    public String toString() {
        return (ok ? "OK: " : "REJECTED (" + code + "): ") + message;
    }
}
