import copy
import unittest
from pydantic import ValidationError
from h3_extensions import H3Options, apply_extensions, revision
from h3_continuation import mode_capabilities, compatibility_key
from schemas import ImageGenerationRequest
from test_video_reference import setup_model, BASE
from workflow_engine import WorkflowEngine


def assert_acyclic(graph):
    done=set();visiting=set()
    def visit(key):
        if key in visiting:raise AssertionError("workflow cycle: "+key)
        if key in done:return
        visiting.add(key)
        for value in graph[key].get("inputs",{}).values():
            if isinstance(value,list) and len(value)==2 and isinstance(value[0],str) and value[0] in graph:visit(value[0])
        visiting.remove(key);done.add(key)
    for key in graph:visit(key)


class ContinuationModesTest(unittest.TestCase):
    def graph(self,mode,context=True,control=False):
        model,config=setup_model()
        engine=WorkflowEngine(config);graph=engine.get_template(model)
        options={"schema_version":2,"capability_revision":revision(model,BASE),"continuation_enabled":True,
                 "length":124,"continuity":{"mode":mode},"save_latent":mode.startswith("LATENT")}
        if context:
            if mode.startswith("LATENT"):options["latent_context"]={"url":"http://minio/latent","content_sha256":"a"*64,"frames":22,"anchor":{"url":"http://minio/anchor","content_sha256":"b"*64}}
            else:options["continuation"]={"video_url":"http://minio/context","content_sha256":"a"*64,"frames":22}
        if control:options["control_net"]={"enabled":True,"reference_id":"v","control_type":"POSE"}
        request=ImageGenerationRequest(model=model.model_name,size="960x544",prompt="continue",seed=7,task_id="test-task",h3_options=options)
        before=copy.deepcopy(graph)
        apply_extensions(graph,request,model,[{"reference_id":"v","filename":"action.mp4","frames":124}],"clean.mp4","raw.safetensors","anchor.safetensors")
        return model,request,before,graph

    def test_all_four_graphs_preserve_sampler_and_have_no_cycles(self):
        for mode in ("GUIDE","GUIDE_CHROMA","LATENT_REDRAW","LATENT_PROTECTED"):
            for control in (False,True):
                with self.subTest(mode=mode,control=control):
                    model,request,before,graph=self.graph(mode,control=control)
                    assert_acyclic(graph)
                    self.assertEqual(graph["125"]["inputs"]["sampler"],before["125"]["inputs"]["sampler"])
                    self.assertEqual(graph["145"],before["145"])
                    if mode.startswith("LATENT"):
                        self.assertNotIn("h3_guide",graph)
                        self.assertEqual(graph["125"]["inputs"]["latent_image"],["h3_native_continue",0])
                        self.assertEqual(graph["122"]["inputs"]["samples"],["h3_native_restore",0])
                        self.assertEqual(graph["h3_save_latent"]["inputs"]["latent"],["h3_native_restore",0])
                    elif mode=="GUIDE_CHROMA":
                        self.assertEqual(graph["h3_guide"]["inputs"]["image"],["h3_chroma",0])
                        self.assertEqual(graph["h3_chroma"]["inputs"]["images"],["h3_context_video",0])
                        self.assertEqual(graph["125"]["inputs"]["latent_image"],before["125"]["inputs"]["latent_image"])

    def test_first_segment_archives_without_applying_previous_context(self):
        _,_,_,graph=self.graph("LATENT_PROTECTED",context=False)
        self.assertNotIn("h3_native_continue",graph)
        self.assertIn("h3_save_latent",graph)
        assert_acyclic(graph)

    def test_native_compatibility_does_not_change_with_control_media_or_prompt(self):
        model,_,before,plain=self.graph("LATENT_PROTECTED")
        _,_,_,controlled=self.graph("LATENT_PROTECTED",control=True)
        # Compatibility is calculated before installing native graph nodes; restore the sampler link.
        for graph in (plain,controlled):graph["125"]["inputs"]["latent_image"]=["136",1]
        a=compatibility_key(plain,model.h3_extensions,"960x544")
        controlled["h3_control_video"]["inputs"]["video"]="different-action.mp4"
        b=compatibility_key(controlled,model.h3_extensions,"960x544")
        self.assertEqual(a,b)
        controlled["145"]["inputs"]["strength_model"]=.75
        self.assertNotEqual(a,compatibility_key(controlled,model.h3_extensions,"960x544"))

    def test_conflicting_sources_inactive_parameters_and_bad_grid_rejected(self):
        base={"schema_version":2,"capability_revision":"r","length":124,"continuation_enabled":True}
        bad=[{"continuity":{"mode":"GUIDE","redraw":{"strength":.1}}},
             {"continuity":{"mode":"LATENT_REDRAW"},"continuation":{"video_url":"x","content_sha256":"a"*64,"frames":22}},
             {"continuity":{"mode":"GUIDE_CHROMA","chroma":{"strength":.1,"seamStrength":.5}}},
             {"continuity":{"mode":"LATENT_PROTECTED"},"context_windows":{"enabled":True}}]
        for item in bad:
            with self.subTest(item=item), self.assertRaises(ValidationError):H3Options.model_validate({**base,**item})

    def test_modes_only_require_their_own_nodes(self):
        caps=mode_capabilities({"MiniMaxH3AddGuide","VHS_LoadVideo","FreyjaH3ChromaNoise"})
        self.assertTrue(caps["GUIDE_CHROMA"]["available"])
        self.assertFalse(caps["LATENT_REDRAW"]["available"])
        self.assertIn("FreyjaH3Continue",caps["LATENT_REDRAW"]["reason"])


if __name__=="__main__":unittest.main()
