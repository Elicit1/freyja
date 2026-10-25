import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from pydantic import ValidationError
from starlette.requests import Request

import main
from schemas import ImageGenerationRequest


class ExecutionTimeoutTests(unittest.IsolatedAsyncioTestCase):
    async def check_wait_budget(self, supplied, expected):
        model = SimpleNamespace(model_name='test-upscale', handler='standard', node_id='node1', timeout_seconds=3600, mappings={})
        node = SimpleNamespace(node_id='node1', http_url='http://compute.invalid')
        config = Mock()
        config.get_model.return_value = model
        config.get_node_for_model.return_value = node
        config.server.timeout_seconds = 1800
        upstream = AsyncMock()
        upstream.http_base = node.http_url
        upstream.submit_prompt.return_value = 'timeout-probe'
        upstream.wait_for_completion.return_value = []
        pool = Mock()
        pool.get_client_for_node = AsyncMock(return_value=upstream)
        engine = Mock()
        engine.assemble_workflow.return_value = {}
        values = {} if supplied is None else {'execution_timeout_seconds': supplied}
        request = ImageGenerationRequest(model=model.model_name, prompt='upscale', **values)
        with patch.object(main, 'config', config), patch('main.get_workflow_engine', return_value=engine), \
                patch('main.get_client_pool', return_value=pool), \
                patch.object(main.task_tracker, 'register', new_callable=AsyncMock), \
                patch.object(main.task_tracker, 'set_status', new_callable=AsyncMock):
            await main.generate_images(request, Request({'type': 'http', 'headers': []}))
        self.assertEqual(upstream.wait_for_completion.await_args.kwargs['timeout_override'], expected)

    async def test_explicit_timeout_overrides_shorter_or_longer_model_default(self):
        for seconds in (1200, 7200, 86400):
            with self.subTest(seconds=seconds):
                await self.check_wait_budget(seconds, seconds)

    async def test_omitted_timeout_keeps_model_default(self):
        await self.check_wait_budget(None, 3600)

    def test_invalid_timeout_is_rejected(self):
        for value in (0, -1, 1.5, True, '7200'):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                ImageGenerationRequest(prompt='upscale', execution_timeout_seconds=value)


if __name__ == '__main__':
    unittest.main()
