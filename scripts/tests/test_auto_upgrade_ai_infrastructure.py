"""Opt-in real MySQL tests, using uniquely named disposable databases only.

FREYJA_UPGRADE_TEST=true python -m unittest discover -s scripts/tests
Requires PyMySQL, a POSIX shell and mysql on PATH. No business database is used.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import uuid

import pymysql

ROOT = Path(__file__).resolve().parents[2]
SQL_DIR = ROOT / "src/main/resources/sql"
SCRIPT = ROOT / "docker/mysql/migrations/auto-upgrade-ai-infrastructure.sh"
SQL_FILES = ("agent-foundation.sql", "agent-production-preparation.sql", "mcp-business.sql", "upgrade_unified_capabilities.sql")


@unittest.skipUnless(os.environ.get("FREYJA_UPGRADE_TEST") == "true", "real MySQL tests are opt-in")
class AutoUpgradeTest(unittest.TestCase):
    def setUp(self):
        self.database = "freyja_upgrade_test_" + uuid.uuid4().hex
        self.connection = pymysql.connect(
            host=os.environ.get("MYSQL_HOST", "127.0.0.1"), port=int(os.environ.get("MYSQL_PORT", "3306")),
            user=os.environ.get("MYSQL_USER", "root"), password=os.environ.get("MYSQL_PWD", "root"),
            charset="utf8mb4", autocommit=True, connect_timeout=5)
        self.query("CREATE DATABASE `" + self.database + "` CHARACTER SET utf8mb4")
        self.addCleanup(self.drop_test_database)
        self.connection.select_db(self.database)
        self.query("CREATE TABLE res_keyframe (id BIGINT PRIMARY KEY, frame_url VARCHAR(512) NOT NULL)")
        self.query("INSERT INTO res_keyframe VALUES (1, 'https://example.test/original.png')")
        self.query("CREATE TABLE sys_config (config_key VARCHAR(100) PRIMARY KEY, config_value TEXT)")
        self.query("INSERT INTO sys_config VALUES ('mcp.server.settings', 'original-user-credentials')")
        self.query("CREATE TABLE drama (id BIGINT PRIMARY KEY, title VARCHAR(100))")
        self.query("INSERT INTO drama VALUES (1, '已有项目')")

    def drop_test_database(self):
        try:
            # The identifier is generated above, never taken from environment/user input.
            if not self.database.startswith("freyja_upgrade_test_"):
                raise RuntimeError("Refusing to drop a non-test database")
            self.query("DROP DATABASE IF EXISTS `" + self.database + "`")
        finally:
            self.connection.close()

    def query(self, sql):
        with self.connection.cursor() as cursor:
            cursor.execute(sql)
            return cursor.fetchall()

    def run_upgrade(self, sql_dir=SQL_DIR):
        environment = dict(os.environ)
        environment.update(MYSQL_HOST=environment.get("MYSQL_HOST", "127.0.0.1"),
                           MYSQL_PWD=environment.get("MYSQL_PWD", "root"),
                           MYSQL_DATABASE=self.database, SQL_DIR=sql_dir.as_posix())
        return subprocess.run([environment.get("UPGRADE_TEST_SHELL", "sh"), SCRIPT.as_posix()],
                              env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              encoding="utf-8", errors="replace", timeout=60)

    def assert_legacy_data(self):
        self.assertEqual((("已有项目",),), self.query("SELECT title FROM drama"))
        self.assertEqual((("https://example.test/original.png",),), self.query("SELECT frame_url FROM res_keyframe WHERE id=1"))
        self.assertEqual((("original-user-credentials",),), self.query("SELECT config_value FROM sys_config"))

    def test_old_volume_upgrade_and_repeat_preserve_settings_and_history(self):
        first = self.run_upgrade()
        self.assertEqual(0, first.returncode, first.stdout)
        self.assertEqual(((4,),), self.query("SELECT COUNT(*) FROM freyja_schema_migration"))
        tables = {row[0] for row in self.query("SHOW TABLES")}
        expected = {"agent_config", "agent_session", "agent_message", "agent_run", "agent_step", "agent_memory",
                    "agent_preparation_job", "shot_production_plan", "agent_material_requirement",
                    "mcp_business_operation", "ai_capability_catalog_state", "ai_capability_definition",
                    "ai_capability_version", "ai_capability_binding", "ai_capability_rule",
                    "ai_capability_release", "ai_capability_outbox"}
        self.assertTrue(expected <= tables, expected - tables)
        self.query("INSERT INTO res_keyframe VALUES (2,NULL)")
        self.query("INSERT INTO agent_config (id,agent_code,display_name,enabled,provider_id,model_code,model_options_json,"
                   "tool_policy_json,skill_policy_json,memory_policy_json,runtime_policy_json,avatar_config_json) "
                   "VALUES (1,'core-assistant','用户助手',TRUE,42,'original-model','{}','{}','{}','{}','{}','{}')")
        self.query("INSERT INTO agent_session (id,agent_code,title,session_type) VALUES (1,'core-assistant','已有会话','NORMAL')")
        self.query("INSERT INTO ai_capability_catalog_state (id,revision,active_release_id) VALUES (1,9,123)")
        self.query("INSERT INTO ai_capability_release (id,revision,payload_json,content_hash,note,create_time) "
                   "VALUES (123,9,'original-capabilities','hash','用户版本',CURRENT_TIMESTAMP)")
        self.query("INSERT INTO mcp_business_operation (id,request_id,tool_name,request_hash,status,result_json,create_time) "
                   "VALUES (1,'original-request','create_prop','hash','SUCCEEDED','original-result',CURRENT_TIMESTAMP)")
        second = self.run_upgrade()
        self.assertEqual(0, second.returncode, second.stdout)
        self.assertEqual(4, second.stdout.count("Already applied:"))
        self.assertEqual((("用户助手", 1, 42, "original-model"),),
                         self.query("SELECT display_name,enabled,provider_id,model_code FROM agent_config"))
        self.assertEqual((("已有会话",),), self.query("SELECT title FROM agent_session"))
        self.assertEqual(((123,),), self.query("SELECT active_release_id FROM ai_capability_catalog_state"))
        self.assertEqual((("original-capabilities",),), self.query("SELECT payload_json FROM ai_capability_release"))
        self.assertEqual((("original-result",),), self.query("SELECT result_json FROM mcp_business_operation"))
        self.assert_legacy_data()

    def test_partially_failed_migration_is_not_recorded_and_can_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            sql_dir = Path(directory)
            for name in SQL_FILES:
                shutil.copyfile(SQL_DIR / name, sql_dir / name)
            with (sql_dir / "mcp-business.sql").open("a", encoding="utf-8") as sql:
                sql.write("\nTHIS IS DELIBERATELY INVALID SQL;\n")
            failed = self.run_upgrade(sql_dir)
            self.assertNotEqual(0, failed.returncode, failed.stdout)
            self.assertIn("The backend will not start", failed.stdout)
            self.assertEqual(((2,),), self.query("SELECT COUNT(*) FROM freyja_schema_migration"))
            shutil.copyfile(SQL_DIR / "mcp-business.sql", sql_dir / "mcp-business.sql")
            retried = self.run_upgrade(sql_dir)
            self.assertEqual(0, retried.returncode, retried.stdout)
            self.assertEqual(((4,),), self.query("SELECT COUNT(*) FROM freyja_schema_migration"))
            self.assert_legacy_data()

    def test_missing_base_schema_fails_before_recording_migrations(self):
        self.query("DROP TABLE res_keyframe")
        result = self.run_upgrade()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("Initialize the base business schema", result.stdout)
        self.assertNotIn("freyja_schema_migration", {row[0] for row in self.query("SHOW TABLES")})

    def test_new_install_with_existing_tables_can_upgrade(self):
        # Simulates init.sql already creating the AI tables in a fresh MySQL volume.
        self.assertEqual(0, self.run_upgrade().returncode)
        self.query("DROP TABLE freyja_schema_migration")
        result = self.run_upgrade()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(((4,),), self.query("SELECT COUNT(*) FROM freyja_schema_migration"))
        self.assert_legacy_data()


if __name__ == "__main__":
    unittest.main()
