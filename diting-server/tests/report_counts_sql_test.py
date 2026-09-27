"""Execute the actual mapper aggregation against isolated in-memory SQLite, not MySQL acceptance."""
import html
import pathlib
import re
import sqlite3
import unittest


class PublishedReportCountsTest(unittest.TestCase):
    def setUp(self):
        source = (pathlib.Path(__file__).parents[1] / 'src/main/java/com/diting/mapper/SentenceMapper.java').read_text()
        match = re.search(r'@Select\("""(.*?)"""\)', source, re.S)
        assert match is not None, 'Mapper SQL annotation missing'
        sql = match.group(1)
        sql = re.sub(r'<foreach.*?</foreach>', '(1,2,3,4)', sql, flags=re.S)
        self.sql = html.unescape(sql.replace('<script>', '').replace('</script>', ''))
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            CREATE TABLE recognition_tasks(id INTEGER, deleted_at TEXT);
            CREATE TABLE sentences(id INTEGER, task_id INTEGER, flagged INTEGER);
            CREATE TABLE report_versions(id INTEGER, task_id INTEGER, version INTEGER, status TEXT);
            CREATE TABLE violations(id INTEGER, report_version_id INTEGER, sentence_id INTEGER);
            INSERT INTO recognition_tasks VALUES (1,NULL),(2,NULL),(3,NULL),(4,'deleted');
            INSERT INTO sentences VALUES (10,1,0),(11,1,0),(20,2,0),(30,3,0);
            INSERT INTO report_versions VALUES (100,1,1,'PUBLISHED'),(101,1,2,'PUBLISHED'),(102,1,3,'FAILED'),(200,2,1,'PUBLISHED');
            INSERT INTO violations VALUES (1,100,10),(2,101,11),(3,101,11),(4,101,NULL),(5,102,10),(6,200,NULL);
        ''')

    def tearDown(self):
        self.db.close()

    def test_latest_published_not_failed_or_cached_flag(self):
        rows = {row['task_id']: row for row in self.db.execute(self.sql)}
        self.assertEqual((rows[1]['total'], rows[1]['flagged'], rows[1]['violations'], rows[1]['report_version_id']), (2, 1, 3, 101))

    def test_call_level_violation_and_no_report_are_distinct(self):
        rows = {row['task_id']: row for row in self.db.execute(self.sql)}
        self.assertEqual((rows[2]['flagged'], rows[2]['violations']), (0, 1))
        self.assertIsNone(rows[3]['report_version_id'])
        self.assertNotIn(4, rows)


if __name__ == '__main__':
    unittest.main()
