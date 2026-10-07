package com.example.engine.vfx

import com.vfx.engine.core.params.ParamDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfxEffectsHostTest {

  private val brightness = "color.brightness"
  private val contrast = "color.contrast"

  @Test
  fun registryExposesBuiltinCatalogWithoutSkips() {
    assertTrue(VfxEffectsHost.catalog().isNotEmpty())
    assertTrue("skipped: ${VfxEffectsHost.registrationSkipped}", VfxEffectsHost.registrationSkipped.isEmpty())
    assertTrue(VfxEffectsHost.categories().isNotEmpty())
  }

  @Test
  fun everyCatalogEffectCanBeInstantiatedAndRoundTrips() {
    VfxEffectsHost.catalog().forEach { def ->
      val json = VfxEffectsHost.addEffect(null, def.id)
      assertNotNull("add ${def.id}", json)
      val back = VfxEffectsHost.decode(json)
      assertEquals(1, back.size)
      assertEquals(def.id, back.effectAt(0).definition.id)
    }
  }

  @Test
  fun emptyStackEncodesToNullAndBlankDecodesEmpty() {
    assertNull(VfxEffectsHost.encode(VfxEffectsHost.decode(null)))
    assertTrue(VfxEffectsHost.decode("").isEmpty())
    assertTrue(VfxEffectsHost.decode("not json").isEmpty())
  }

  @Test
  fun setParamPersistsAndIsClampedToDescriptorRange() {
    var json = VfxEffectsHost.addEffect(null, brightness)
    json = VfxEffectsHost.setParam(json, 0, "amount", 0.4f)
    assertEquals(0.4f, VfxEffectsHost.decode(json).effectAt(0).getParam<Float>("amount"), 1e-6f)
    json = VfxEffectsHost.setParam(json, 0, "amount", 99f)
    assertEquals(1f, VfxEffectsHost.decode(json).effectAt(0).getParam<Float>("amount"), 1e-6f)
  }

  @Test
  fun unknownParamAndBadIndexAreIgnored() {
    val json = VfxEffectsHost.addEffect(null, brightness)
    assertEquals(json, VfxEffectsHost.setParam(json, 0, "nope", 1f))
    assertEquals(json, VfxEffectsHost.setParam(json, 5, "amount", 1f))
    assertEquals(json, VfxEffectsHost.removeAt(json, 7))
  }

  @Test
  fun reorderDuplicateEnableIntensityRemove() {
    var json = VfxEffectsHost.addEffect(null, brightness)
    json = VfxEffectsHost.addEffect(json, contrast)
    json = VfxEffectsHost.move(json, 0, 1)
    assertEquals(contrast, VfxEffectsHost.decode(json).effectAt(0).definition.id)

    json = VfxEffectsHost.duplicate(json, 0)
    assertEquals(3, VfxEffectsHost.decode(json).size)

    json = VfxEffectsHost.setEnabled(json, 1, false)
    json = VfxEffectsHost.setIntensity(json, 1, 0.25f)
    val inst = VfxEffectsHost.decode(json).effectAt(1)
    assertFalse(inst.enabled)
    assertEquals(0.25f, inst.intensity, 1e-6f)

    json = VfxEffectsHost.removeAt(json, 0)
    json = VfxEffectsHost.removeAt(json, 0)
    json = VfxEffectsHost.removeAt(json, 0)
    assertNull(json)
  }

  @Test
  fun resetRestoresDefaults() {
    var json = VfxEffectsHost.addEffect(null, brightness)
    json = VfxEffectsHost.setParam(json, 0, "amount", 0.7f)
    json = VfxEffectsHost.setIntensity(json, 0, 0.3f)
    json = VfxEffectsHost.resetEffect(json, 0)
    val inst = VfxEffectsHost.decode(json).effectAt(0)
    assertEquals(0f, inst.getParam<Float>("amount"), 1e-6f)
    assertEquals(1f, inst.intensity, 1e-6f)
  }

  /** Guards the panel: every param type the catalog uses must be one the panel can edit or safely skip. */
  @Test
  fun catalogParamTypesAreKnownToThePanel() {
    val editable = setOf("float", "int", "bool", "enum", "color", "vec2", "vec3", "vec4")
    val skipped = setOf("curve", "gradient", "texture", "string", "mat4")
    VfxEffectsHost.catalog().flatMap { it.params }.forEach { p: ParamDescriptor<*> ->
      assertTrue("unknown type ${p.jsonType}", p.jsonType in editable || p.jsonType in skipped)
    }
  }
}
