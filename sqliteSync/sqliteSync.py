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
def build_diff_tables(cursor,tableInfo:TableInfo, base_prefix, supplement_prefix, primary_key='id'):
    tblname=tableInfo['name']
    supplement_table=f'{supplement_prefix}{tblname}'
    base_table=f'{base_prefix}{tblname}'
    non_pk_columns = [col for col in tableInfo['columns'] if col != primary_key]

     # 1. diff_delete: supplement 有但 base 没有的主键
    cursor.execute(f"""
        CREATE TABLE diff_{tblname}_delete AS
        SELECT s.{primary_key}
        FROM {supplement_table} s
        right JOIN {base_table} b ON b.{primary_key} = s.{primary_key}
        WHERE s.{primary_key} IS NULL
    """)

    # 2. diff_add: base 有但 supplement 没有的完整行
    cursor.execute(f"""
        CREATE TABLE diff_{tblname}_add AS
        SELECT s.*
        FROM {base_table} b
        right JOIN {supplement_table} s ON s.{primary_key} = b.{primary_key}
        WHERE b.{primary_key} IS NULL
    """)

    # 3. diff_update: 主键相同但非主键字段不同的行（取 base 的新值）
    # 构造 NULL 安全的比较条件
    compare_clauses = []
    for col in non_pk_columns:
        # 处理 NULL：只有当两边都为 NULL 时才相等，否则视为不同
        clause = f"(s.{col} IS NOT b.{col} AND (s.{col} IS NOT NULL OR b.{col} IS NOT NULL))"
        compare_clauses.append(clause)
    compare_condition = " OR ".join(compare_clauses)

    cursor.execute(f"""
        CREATE TABLE diff_{tblname}_update AS
        SELECT s.*
        FROM {base_table} b
        INNER JOIN {supplement_table} s ON s.{primary_key} = b.{primary_key}
        WHERE {compare_condition}
    """)
def apply_add(cursor,tableInfo:TableInfo, base_prefix, supplement_prefix, primary_key='id'):
    columns=tableInfo['columns']
    columns_str = ', '.join(columns)
    tblname=tableInfo['name']

    # 插入
    base_table=f'{base_prefix}{tblname}'

    placeholders = ', '.join(['?' for _ in columns])
    cursor.execute(f"""
        INSERT INTO {base_table} ({columns_str})
        SELECT {columns_str} FROM diff_{tblname}_add
    """)
def set_add_tag(cursor,tableInfo:TableInfo, base_prefix, supplement_prefix, primary_key='id'):
    columns=tableInfo['columns']
    columns_str = ', '.join(columns)
    tblname=tableInfo['name']

    # 插入

    placeholders = ', '.join(['?' for _ in columns])
    cursor.execute(f"""
        update diff_{tblname}_add set tag=replace(tag,'/batchImport/','//') + '/batchImport/'
    """)
def apply_update(cursor,tableInfo:TableInfo, base_prefix, supplement_prefix, primary_key='id'):
    columns=tableInfo['columns']
    columns_str = ', '.join(columns)
    tblname=tableInfo['name']

    # 插入
    base_table=f'{base_prefix}{tblname}'
    non_pk_columns=[col for col in columns if col != primary_key]

     # 更新（使用子查询或直接 JOIN，SQLite 支持 UPDATE FROM）
    set_clause = ', '.join([f"{col} = u.{col}" for col in non_pk_columns])
    cursor.execute(f"""
        UPDATE {base_table} AS t
        SET {set_clause}
        FROM diff_{tblname}_update AS u
        WHERE t.{primary_key} = u.{primary_key}
    """)
def apply_delete(cursor,tableInfo:TableInfo, base_prefix, supplement_prefix, primary_key='id'):
    columns=tableInfo['columns']
    columns_str = ', '.join(columns)
    tblname=tableInfo['name']

    # 插入
    base_table=f'{base_prefix}{tblname}'

     # 删除
    cursor.execute(f"""
        DELETE FROM {base_table}
        WHERE {primary_key} IN (SELECT {primary_key} FROM diff_{tblname}_delete)
    """)


def sync_with_diff_tables(db_path, base_table, supplement_table, primary_key='id'):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    try:
        tableInfo=createTableInfo(cursor, '', base_table, primary_key)
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
from typing import Iterable
def diffStellarDatabase(base_db_path:str,supplement_db_path:str,tbls:Iterable[str],primary_key='id'):
    conn = sqlite3.connect(base_db_path)
    cursor = conn.cursor()
    #attach another db
    cursor.execute(f'''ATTACH DATABASE '{supplement_db_path}' AS remote ;''')

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
import json
def guessNodeType(ss:str):
    if(ss==None):
        return None
    dataDict=json.loads(ss)
    maxBranch=(None,-1)
    for key in dataDict.keys():
        le=len(json.dumps(dataDict[key]))
        if(le>maxBranch[1]):
            maxBranch=(key,le)
    return maxBranch[0]

def someUpdate(base_db_path:str):
    conn = sqlite3.connect(base_db_path)
    cursor = conn.cursor()
    cursor.execute('select id,extra from diff_node_update')
    for row in cursor:
        print(row[0],guessNodeType(row[1]))
    conn.commit()
    conn.close()
def setStellarNodeAddTag(base_db_path):
    conn = sqlite3.connect(base_db_path)
    cursor = conn.cursor()
    tableInfo=createTableInfo(cursor,'', 'node','id')
    set_add_tag(cursor,tableInfo, '', '')
    conn.commit()
    conn.close()

def someApplyAdd(base_db_path:str,tbls:Iterable[str]):

    conn = sqlite3.connect(base_db_path)
    cursor = conn.cursor()

    for tbl in tbls:
        tableInfo:TableInfo=createTableInfo(cursor,'',tbl,'id')
        apply_add(cursor,tableInfo, '', '')
    
    conn.commit()
    conn.close()


if __name__ == '__main__':
    tableName:Iterable[str]='''edge
foreign_edge
foreign_node
node'''.splitlines()
    basePath=r'C:\Users\lzy\Desktop\dbtest\\'
    diffStellarDatabase(basePath+'memory',basePath+'memory2',tableName,'id')
    setStellarNodeAddTag(basePath+'memory')
    someUpdate(basePath+'memory')
    # someApplyAdd(basePath+'memory',tableName)


    # basePath=r'C:\Users\lzy\Desktop\dbtest\\'
    # diffStellarDatabase(basePath+'b',basePath+'a',['c'],'id')
    # someApplyAdd(basePath+'b',['c'])
    