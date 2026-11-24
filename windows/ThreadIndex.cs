using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace CodexRemote;

// The desktop tools identify the local thread. Its read-only index supplies
// model metadata and relationships omitted by read_thread.
static class ThreadIndex
{
    const string Lib = "winsqlite3";
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int sqlite3_open_v2([MarshalAs(UnmanagedType.LPUTF8Str)] string path, out IntPtr db, int flags, IntPtr vfs);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int sqlite3_prepare_v2(IntPtr db, [MarshalAs(UnmanagedType.LPUTF8Str)] string sql, int length, out IntPtr statement, IntPtr tail);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int sqlite3_bind_text(IntPtr stmt, int index, [MarshalAs(UnmanagedType.LPUTF8Str)] string value, int length, IntPtr destructor);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int sqlite3_step(IntPtr stmt);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern IntPtr sqlite3_column_text(IntPtr stmt, int col);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int sqlite3_finalize(IntPtr stmt);
    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)] static extern int sqlite3_close(IntPtr db);

    static List<string[]> Rows(string id, string sql, int columns)
    {
        var path = Path.Combine(ThreadSettings.CodexHome(), "state_5.sqlite");
        if (!Guid.TryParse(id, out _)) throw new ApiError(400, "会话标识无效");
        IntPtr db = default, stmt = default;
        try
        {
            if (sqlite3_open_v2(path, out db, 1, IntPtr.Zero) != 0 ||
                sqlite3_prepare_v2(db, sql, -1, out stmt, IntPtr.Zero) != 0 ||
                sqlite3_bind_text(stmt, 1, id, -1, new IntPtr(-1)) != 0)
                throw new ApiError(503, "暂时无法读取电脑的会话索引");
            var rows = new List<string[]>();
            int result;
            while ((result = sqlite3_step(stmt)) == 100)
                rows.Add(Enumerable.Range(0, columns).Select(i => Marshal.PtrToStringUTF8(sqlite3_column_text(stmt, i)) ?? "").ToArray());
            if (result != 101) throw new ApiError(503, "会话索引正在更新，请重试");
            return rows;
        }
        finally { if (stmt != default) sqlite3_finalize(stmt); if (db != default) sqlite3_close(db); }
    }
    public static JsonNode Settings(string id, string cwd)
    {
        var row = Rows(id, "SELECT model, reasoning_effort, model_provider FROM threads WHERE id=?1", 3).SingleOrDefault()
            ?? throw new ApiError(404, "会话索引中没有此任务");
        return J.Node(new { latestModel = row[0], latestReasoningEffort = row[1], modelProvider = row[2], cwd });
    }
    public static JsonArray Children(string id) => new(Rows(id, """
        SELECT t.id, coalesce(nullif(t.name,''),nullif(t.agent_nickname,''),t.title),
               CASE WHEN t.thread_source='subagent' OR e.child_thread_id IS NOT NULL THEN 'agent' ELSE 'sideChat' END
        FROM threads t LEFT JOIN thread_spawn_edges e ON e.child_thread_id=t.id AND e.parent_thread_id=?1
        WHERE t.archived=0 AND (t.attached_thread_parent_id=?1 OR e.child_thread_id IS NOT NULL)
        ORDER BY t.updated_at DESC
        """, 3).Select(r => J.Node(new { id = r[0], name = r[1], kind = r[2] })).ToArray());
}
