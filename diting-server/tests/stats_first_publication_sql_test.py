"""StatsMapper text compared to a static SQL probe on isolated SQLite; NOT MySQL acceptance.

统计面收敛后只校总览用到的两个“首版”聚合（有效报告量 / 按日序列）；
risk / violations / deletion / quota 等分组的 SQL 已随分组下线，不再校（它们的前缀也不再存在）。
"""
import pathlib
import re
import sqlite3
import unittest

SOURCE = (pathlib.Path(__file__).parents[1] / 'src/main/java/com/diting/mapper/StatsMapper.java').read_text()


def mapper_sql(method):
    end = SOURCE.index(' ' + method + '(')
    start = SOURCE.rfind('@Select(', 0, end)
    assert start >= 0, method + ' mapper annotation not found'
    parts = re.findall(r'"((?:\\.|[^"\\])*)"', SOURCE[start:end])
    return ''.join(parts).replace('#{from}', ':from').replace('#{to}', ':to')


REPORT_SQL = ('SELECT COUNT(*) FROM (SELECT task_id, published_at, '
              'ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY published_at, id) AS rn '
              "FROM report_versions WHERE status='PUBLISHED') x "
              'WHERE rn=1 AND published_at >= :from AND published_at < :to')
DAILY_SQL = ('SELECT DATE(published_at) AS day, COUNT(*) AS cnt FROM ('
             'SELECT task_id, published_at, '
             'ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY published_at, id) AS rn '
             "FROM report_versions WHERE status='PUBLISHED') x "
             'WHERE rn=1 AND published_at >= :from AND published_at < :to '
             'GROUP BY DATE(published_at) ORDER BY day')


class FirstPublishedStatsTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
            CREATE TABLE report_versions(id INTEGER, task_id INTEGER, status TEXT,
                published_at TEXT, risk_score INTEGER, threshold_snapshot INTEGER);
            INSERT INTO report_versions VALUES
              (1,1,'PUBLISHED','2026-08-01 10:00:00',1,5),
              (2,1,'PUBLISHED','2026-09-10 10:00:00',90,5),
              (3,2,'PUBLISHED','2026-09-10 11:00:00',8,5),
              (4,2,'PUBLISHED','2026-09-11 10:00:00',90,5),
              (5,3,'PUBLISHED','2026-09-11 09:00:00',1,5),
              (6,3,'PUBLISHED','2026-09-12 10:00:00',90,5),
              (7,4,'FAILED','2026-09-12 10:00:00',90,5);
        ''')
        self.period = {'from': '2026-09-01 00:00:00', 'to': '2026-10-01 00:00:00'}

    def tearDown(self):
        self.db.close()

    def test_window_counts_only_historical_first_publication(self):
        self.assertEqual(mapper_sql('publishedReports'), REPORT_SQL)
        self.assertEqual(self.db.execute(
            'SELECT COUNT(*) FROM (SELECT task_id, published_at, '
            'ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY published_at, id) AS rn '
            "FROM report_versions WHERE status='PUBLISHED') x "
            'WHERE rn=1 AND published_at >= :from AND published_at < :to', self.period).fetchone()[0], 2)

    def test_daily_series_matches_first_publication_not_reruns(self):
        self.assertEqual(mapper_sql('publishedByDay'), DAILY_SQL)
        rows = self.db.execute(
            'SELECT DATE(published_at) AS day, COUNT(*) AS cnt FROM ('
            'SELECT task_id, published_at, '
            'ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY published_at, id) AS rn '
            "FROM report_versions WHERE status='PUBLISHED') x "
            'WHERE rn=1 AND published_at >= :from AND published_at < :to '
            'GROUP BY DATE(published_at) ORDER BY day', self.period).fetchall()
        self.assertEqual(rows, [('2026-09-10', 1), ('2026-09-11', 1)])


if __name__ == '__main__':
    unittest.main()
