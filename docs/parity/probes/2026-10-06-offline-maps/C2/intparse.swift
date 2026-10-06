// SPDX-License-Identifier: GPL-3.0-or-later
let samples = ["3", "+3", "-1", "03", " 3", "", "\u{0663}", "1:x", "99999999999999999999"]
for s in samples { print("[\(s)] ->", Int(s).map(String.init) ?? "nil") }
