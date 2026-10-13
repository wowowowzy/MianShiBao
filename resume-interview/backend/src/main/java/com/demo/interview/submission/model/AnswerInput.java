package com.demo.interview.submission.model;

/**
 * 一道题的作答输入。
 * store 指明题目来源（bank=常规题库 / project=项目场景题 / bigtech=大厂题），
 * 用于判分时从对应表按 questionId 取题；fullScore 为该题满分（缺省 20）。
 */
public record AnswerInput(Long questionId, String store, String answerText, Integer fullScore) {
}