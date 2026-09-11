package com.lc33.tokenvault.platform

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 起没有手写部分索引；保留这个入口是为了让平台建库代码不必关心“这一版有没有额外 DDL”。
 * 以后如果再出现 Room 表达不了的索引，在这里补一条幂等 DDL 即可。
 */
fun SupportSQLiteDatabase.applyHandWrittenSchema() = Unit
