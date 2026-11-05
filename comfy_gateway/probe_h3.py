"""Read-only installed-node / weight fingerprint for online acceptance records."""
import argparse
import asyncio
import json
from pathlib import Path
import httpx
import yaml
from config import NodeConfig,get_base_dir
from comfy_client import ComfyAsyncClient
from h3_extensions import backend_acceptance,revision


async def probe(path,model_name):
    node=NodeConfig(**yaml.safe_load(path.read_text(encoding="utf-8")))
    model=next(model for model in node.models if model.model_name==model_name)
    model.node_id=node.node_id
    client=ComfyAsyncClient(node.http_url,node.get_ws_url())
    client._http_client=httpx.AsyncClient(trust_env=False,timeout=10)
    try:
        result=await backend_acceptance(model,client)
        result.update(modelCode=model_name,nodeId=node.node_id,workflowRevision=revision(model,get_base_dir()))
        print(json.dumps(result,ensure_ascii=False,indent=2))
    finally:await client._http_client.aclose()


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--node-config",type=Path,required=True);parser.add_argument("--model",required=True)
    args=parser.parse_args();asyncio.run(probe(args.node_config,args.model))
