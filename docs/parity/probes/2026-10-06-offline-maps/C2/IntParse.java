// SPDX-License-Identifier: GPL-3.0-or-later
public class IntParse {
  public static void main(String[] a) {
    String[] s = {"3", "+3", "-1", "03", " 3", "", "٣", "1:x", "99999999999999999999"};
    for (String x : s) {
      Integer v;
      try { v = Integer.parseInt(x); } catch (NumberFormatException e) { v = null; }
      System.out.println("[" + x + "] parseInt -> " + v + "  Character.digit(first)=" + (x.isEmpty() ? "-" : Character.digit(x.charAt(0), 10)));
    }
  }
}
