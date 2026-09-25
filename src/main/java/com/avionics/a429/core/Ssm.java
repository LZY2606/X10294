package com.avionics.a429.core;

/**
 * SSM（符号/状态矩阵，位 30-31）语义。含义依赖数据类型，
 * 而数据类型由标签字典条目给出，因此 SSM 解释随字典版本变化。
 */
public final class Ssm {
    private Ssm() {}

    public enum Meaning { NORMAL, NCD, FUNCTIONAL_TEST, FAILURE_WARNING, UNKNOWN }

    /** BNR：位 29 为符号位，SSM 00=故障警告 01=NCD 10=功能测试 11=正常。 */
    public static Meaning forBnr(int ssm) {
        return switch (ssm) {
            case 0 -> Meaning.FAILURE_WARNING;
            case 1 -> Meaning.NCD;
            case 2 -> Meaning.FUNCTIONAL_TEST;
            case 3 -> Meaning.NORMAL;
            default -> Meaning.UNKNOWN;
        };
    }

    /** BCD：SSM 00=正/正常 01=NCD 10=功能测试 11=负/正常。 */
    public static Meaning forBcd(int ssm) {
        return switch (ssm) {
            case 0, 3 -> Meaning.NORMAL;
            case 1 -> Meaning.NCD;
            case 2 -> Meaning.FUNCTIONAL_TEST;
            default -> Meaning.UNKNOWN;
        };
    }

    /** 离散量：00=正常 01=NCD 10=功能测试 11=故障警告。 */
    public static Meaning forDiscrete(int ssm) {
        return switch (ssm) {
            case 0 -> Meaning.NORMAL;
            case 1 -> Meaning.NCD;
            case 2 -> Meaning.FUNCTIONAL_TEST;
            case 3 -> Meaning.FAILURE_WARNING;
            default -> Meaning.UNKNOWN;
        };
    }

    public static Meaning resolve(int ssm, String dataType) {
        if (dataType == null) return Meaning.UNKNOWN;
        return switch (dataType) {
            case "BNR" -> forBnr(ssm);
            case "BCD" -> forBcd(ssm);
            case "DISCRETE" -> forDiscrete(ssm);
            default -> Meaning.UNKNOWN;
        };
    }

    /** BNR 符号位（数据域最高位，即字位 29）。 */
    public static boolean bnrNegative(long dataField19) {
        return ((dataField19 >>> 18) & 1L) == 1L;
    }

    /** BCD 的负号由 SSM=11 表达。 */
    public static boolean bcdNegative(int ssm) {
        return ssm == 3;
    }
}
