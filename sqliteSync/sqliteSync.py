import sqlite3
from typing import TypedDict
import traceback
class TableInfo(TypedDict):
    name: str
    primary_key: str
    columns: list[str]
def createTableInfo(cursor, prefix,table_name,primary_key):
    print(f"Table: {prefix}{table_name}")
    cursor.execute(f"PRAGMA table_info({prefix}{table_name})")
    columns = [row[1] for row in cursor.fetchall()]
    return TableInfo(name=table_name, primary_key=primary_key, columns=columns)
def drop_diff_tables(cursor,tableInfo:TableInfo):
    tblname=tableInfo['name']
    for name in map(lambda x:f'diff_{tblname}_{x}',['add','update','delete']):
        cursor.execute(f"DROP TABLE IF EXISTS {name}")
def build_diff_tables(cursor,tableInfo:TableInfo, source_prefix, target_prefix, primary_key='id'):
    tblname=tableInfo['name']
    target_table=f'{target_prefix}{tblname}'
    source_table=f'{source_prefix}{tblname}'
    non_pk_columns = [col for col in tableInfo['columns'] if col != primary_key]

     # 1. diff_delete: target 有但 source 没有的主键
    cursor.execute(f"""
        CREATE TABLE diff_{tblname}_delete AS
        SELECT t.{primary_key}
        FROM {target_table} t
        LEFT JOIN {source_table} s ON t.{primary_key} = s.{primary_key}
        WHERE s.{primary_key} IS NULL
    """)

    # 2. diff_add: source 有但 target 没有的完整行
    cursor.execute(f"""
        CREATE TABLE diff_{tblname}_add AS
        SELECT s.*
        FROM {source_table} s
        LEFT JOIN {target_table} t ON s.{primary_key} = t.{primary_key}
        WHERE t.{primary_key} IS NULL
    """)

    # 3. diff_update: 主键相同但非主键字段不同的行（取 source 的新值）
    # 构造 NULL 安全的比较条件
    compare_clauses = []
    for col in non_pk_columns:
        # 处理 NULL：只有当两边都为 NULL 时才相等，否则视为不同
        clause = f"(s.{col} IS NOT t.{col} AND (s.{col} IS NOT NULL OR t.{col} IS NOT NULL))"
        compare_clauses.append(clause)
    compare_condition = " OR ".join(compare_clauses)

    cursor.execute(f"""
        CREATE TABLE diff_{tblname}_update AS
        SELECT t.*
        FROM {source_table} s
        INNER JOIN {target_table} t ON s.{primary_key} = t.{primary_key}
        WHERE {compare_condition}
    """)
def apply_add(cursor,tableInfo:TableInfo, source_prefix, target_prefix, primary_key='id'):
    columns=tableInfo['columns']
    columns_str = ', '.join(columns)
    tblname=tableInfo['name']

    # 插入
    target_table=f'{target_prefix}{tblname}'

    placeholders = ', '.join(['?' for _ in columns])
    cursor.execute(f"""
        INSERT INTO {target_table} ({columns_str})
        SELECT {columns_str} FROM diff_{tblname}_add
    """)
def apply_update(cursor,tableInfo:TableInfo, source_prefix, target_prefix, primary_key='id'):
    columns=tableInfo['columns']
    columns_str = ', '.join(columns)
    tblname=tableInfo['name']

    # 插入
    target_table=f'{target_prefix}{tblname}'
    non_pk_columns=[col for col in columns if col != primary_key]

     # 更新（使用子查询或直接 JOIN，SQLite 支持 UPDATE FROM）
    set_clause = ', '.join([f"{col} = u.{col}" for col in non_pk_columns])
    cursor.execute(f"""
        UPDATE {target_table} AS t
        SET {set_clause}
        FROM diff_{tblname}_update AS u
        WHERE t.{primary_key} = u.{primary_key}
    """)
def apply_delete(cursor,tableInfo:TableInfo, source_prefix, target_prefix, primary_key='id'):
    columns=tableInfo['columns']
    columns_str = ', '.join(columns)
    tblname=tableInfo['name']

    # 插入
    target_table=f'{target_prefix}{tblname}'

     # 删除
    cursor.execute(f"""
        DELETE FROM {target_table}
        WHERE {primary_key} IN (SELECT {primary_key} FROM diff_{tblname}_delete)
    """)


def sync_with_diff_tables(db_path, source_table, target_table, primary_key='id'):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    try:
        tableInfo=createTableInfo(cursor, '', source_table, primary_key)
        drop_diff_tables(cursor, tableInfo)
        build_diff_tables(cursor, tableInfo, '', '')
        tblname=tableInfo['name']

        # === 步骤 2：可选 - 打印差异统计 ===
        for name in [f'diff_{tblname}_add', f'diff_{tblname}_update', f'diff_{tblname}_delete']:
            cursor.execute(f"SELECT COUNT(*) FROM {name}")
            count = cursor.fetchone()[0]
            print(f"{name}: {count} rows")

        conn.commit()
        # === 步骤 3：基于 diff 表执行同步 ===
        print('fin ')

    except Exception as e:
        conn.rollback()
        print("❌ 同步失败:", e)
    finally:
        conn.close()
def diffDb(source_db_path:str,future_db_path:str,tbls:list[str],primary_key='id'):
    conn = sqlite3.connect(source_db_path)
    cursor = conn.cursor()
    #attach another db
    cursor.execute(f'''ATTACH DATABASE '{future_db_path}' AS remote ;''')
    target_db_prefix=f'remote.'

    try:
        for tbl in tbls:
            tableInfo=createTableInfo(cursor, '', tbl, primary_key)
            drop_diff_tables(cursor, tableInfo)
            tblname=tbl
            build_diff_tables(cursor, tableInfo, '', 'remote.')

            # === 步骤 2：可选 - 打印差异统计 ===
            report=[]
            for name in [f'diff_{tblname}_add', f'diff_{tblname}_update', f'diff_{tblname}_delete']:
                cursor.execute(f"SELECT COUNT(*) FROM {name}")
                count = cursor.fetchone()[0]
                report.append(f"{name}: {count} rows")

            print(' '.join(report))
            conn.commit()
            # === 步骤 3：基于 diff 表执行同步 ===
            print('fin ')

    except Exception as e:
        conn.rollback()
        print("❌ 同步失败:", e)
        traceback.print_exc()
    finally:
        conn.close()
if __name__ == '__main__':
    basePath=r'C:\Users\lzy\Desktop\dbtest\\'
    diffDb(basePath+'a',basePath+'b',['c'],'id')

