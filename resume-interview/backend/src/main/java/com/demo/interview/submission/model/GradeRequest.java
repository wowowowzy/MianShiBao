package com.demo.interview.submission.model;

import java.util.List;

/**
 * 一次交卷：整个试卷的所有作答 + 用于上下文的能力画像（可选）。
 */
public record GradeRequest(List<AnswerInput> answers) {
}