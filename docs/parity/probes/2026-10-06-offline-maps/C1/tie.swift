// SPDX-License-Identifier: GPL-3.0-or-later
for v in [42.0, 42.0000004, 42.0000006, 42.000001] { print(v, v * 1_000_000, (v * 1_000_000).rounded()) }
