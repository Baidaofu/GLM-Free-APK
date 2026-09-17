# -*- coding: utf-8 -*-
"""Patch MainActivity: clear-token-db button with confirmation."""

P = 'app/src/main/java/com/zaiapi/android/MainActivity.java'
src = open(P, encoding='utf-8').read()

def must_replace(old, new, label):
    global src
    assert old in src, label + ' NOT FOUND'
    src = src.replace(old, new, 1)
    print('ok:', label)

# 1. import AlertDialog + File
must_replace(
    'import android.app.Activity;',
    'import android.app.Activity;\nimport android.app.AlertDialog;', 'import AlertDialog')
must_replace(
    'import java.io.File;',
    'import java.io.File;', 'import File (noop)')

# 2. add clear button to second row (after harvest button)
old_row = ('        Button harvestBtn = makeButton(\n'
           '            "\\u5185\\u7f6e\\u91c7\\u96c6token\\uff08WebView\\uff09", v -> {\n'
           '                try {\n'
           '                    startActivity(new Intent(this, TokenHarvestActivity.class));\n'
           '                } catch (Throwable t) {\n'
           '                    Toast.makeText(this, "\\u65e0\\u6cd5\\u6253\\u5f00: " + t.getMessage(), Toast.LENGTH_LONG).show();\n'
           '                }\n'
           '            });\n'
           '        btnRow2.addView(harvestBtn, new LinearLayout.LayoutParams(-1, -2));')
new_row = old_row + ('\n\n'
    '        Button clearBtn = makeButton("\\u6e05\\u7a7atoken\\u5e93", v -> clearTokenDb());\n'
    '        clearBtn.setTextColor(Color.parseColor("#B91C1C"));\n'
    '        btnRow2.addView(clearBtn, new LinearLayout.LayoutParams(-1, -2));')
must_replace(old_row, new_row, 'clear button')

# 3. clearTokenDb method (insert before refreshStatus)
anchor = '    private void refreshStatus() {'
method = ('    /** \\u6e05\\u7a7a\\u5168\\u90e8 device token\\uff08\\u4e3b\\u5e93 + \\u91c7\\u96c6\\u6682\\u5b58 + \\u5907\\u4efd\\uff09\\u3002 */\n'
    '    private void clearTokenDb() {\n'
    '        if (ServerService.isRunning()) {\n'
    '            Toast.makeText(this, "\\u8bf7\\u5148\\u505c\\u6b62\\u670d\\u52a1\\u518d\\u6e05\\u7a7a", Toast.LENGTH_LONG).show();\n'
    '            return;\n'
    '        }\n'
    '        long count = -1;\n'
    '        try {\n'
    '            File db = ServerService.tokenDbFile(this);\n'
    '            if (db.isFile()) {\n'
    '                android.database.sqlite.SQLiteDatabase c =\n'
    '                        android.database.sqlite.SQLiteDatabase.openDatabase(\n'
    '                                db.getAbsolutePath(), null,\n'
    '                                android.database.sqlite.SQLiteDatabase.OPEN_READONLY);\n'
    '                android.database.Cursor cur = c.rawQuery("SELECT COUNT(*) FROM tokens", null);\n'
    '                if (cur.moveToFirst()) count = cur.getLong(0);\n'
    '                cur.close();\n'
    '                c.close();\n'
    '            }\n'
    '        } catch (Throwable ignored) {\n'
    '        }\n'
    '        final long n = count;\n'
    '        new AlertDialog.Builder(this)\n'
    '                .setTitle("\\u6e05\\u7a7a token \\u5e93")\n'
    '                .setMessage("\\u5c06\\u5220\\u9664\\u5168\\u90e8 device token"\n'
    '                        + (n >= 0 ? "\\uff08\\u5f53\\u524d " + n + " \\u4e2a\\uff09" : "")\n'
    '                        + "\\uff0c\\u542b\\u91c7\\u96c6\\u6682\\u5b58\\u4e0e\\u5907\\u4efd\\uff0c\\u4e0d\\u53ef\\u6062\\u590d\\u3002\\u786e\\u5b9a\\uff1f")\n'
    '                .setPositiveButton("\\u6e05\\u7a7a", (d, w) -> {\n'
    '                    File dir = getFilesDir();\n'
    '                    String[] names = {"tokens.sqlite", "tokens.sqlite-wal",\n'
    '                            "tokens.sqlite-shm", "tokens.sqlite.bak",\n'
    '                            "tokens.harvest.sqlite", "tokens.harvest.sqlite-wal",\n'
    '                            "tokens.harvest.sqlite-shm"};\n'
    '                    int deleted = 0;\n'
    '                    for (String name : names) {\n'
    '                        File f = new File(dir, name);\n'
    '                        if (f.exists() && f.delete()) deleted++;\n'
    '                    }\n'
    '                    LogStore.get().log("APP", "\\u5df2\\u6e05\\u7a7a token \\u5e93\\uff08\\u5220\\u9664 " + deleted + " \\u4e2a\\u6587\\u4ef6\\uff09");\n'
    '                    Toast.makeText(this, "\\u5df2\\u6e05\\u7a7a", Toast.LENGTH_SHORT).show();\n'
    '                })\n'
    '                .setNegativeButton("\\u53d6\\u6d88", null)\n'
    '                .show();\n'
    '    }\n\n'
    + anchor)
must_replace(anchor, method, 'clearTokenDb method')

open(P, 'w', encoding='utf-8', newline='\n').write(src)
print('ALL PATCHED')
