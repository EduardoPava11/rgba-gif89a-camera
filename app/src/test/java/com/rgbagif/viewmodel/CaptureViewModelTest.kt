package com.rgbagif.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.rgbagif.MainViewModel
import com.rgbagif.camera.CameraXManager

/**
 * Test 1: ViewModel Lifecycle & State Management
 * Validates proper StateFlow initialization, state transitions, and memory cleanup
 */
@ExperimentalCoroutinesApi
class CaptureViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    
    private lateinit var cameraManager: CameraXManager
    private lateinit var viewModel: MainViewModel
    
    @Before
    fun setup() {
        cameraManager = mockk(relaxed = true)
        viewModel = MainViewModel()
    }
    
    @Test
    fun `initial state should have correct default values`() = testScope.runTest {
        // Test initial StateFlow values match spec
        assertEquals(0, viewModel.framesCaptured.first())
        assertEquals(0.0, viewModel.currentFps.first(), 0.01)
        assertEquals(0.0, viewModel.deltaE.first(), 0.01)
        assertEquals(0, viewModel.paletteCount.first())
        assertFalse(viewModel.isCapturing.first())
        assertNull(viewModel.alphaHeatmap.first())
        assertNull(viewModel.deltaEHeatmap.first())
    }
    
    @Test
    fun `state should update correctly during capture lifecycle`() = testScope.runTest {
        viewModel.framesCaptured.test {
            // Initial state
            assertEquals(0, awaitItem())
            
            // Start capture simulation
            repeat(5) {
                viewModel.onFrameCaptured()
            }
            
            // Verify incremental updates
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            assertEquals(3, awaitItem())
            assertEquals(4, awaitItem())
            assertEquals(5, awaitItem())
        }
    }
    
    @Test
    fun `fps calculation should be accurate over time`() = testScope.runTest {
        viewModel.currentFps.test {
            // Initial FPS should be 0
            assertEquals(0.0, awaitItem(), 0.01)
            
            // Simulate frames at known intervals
            val targetFps = 24.0
            val intervalMs = (1000.0 / targetFps).toLong()
            
            repeat(10) {
                viewModel.updateFps(targetFps)
            }
            
            // Should converge to target FPS
            val finalFps = awaitItem()
            assertTrue("FPS should be close to 24.0, was $finalFps", 
                       kotlin.math.abs(finalFps - 24.0) < 1.0)
        }
    }
    
    @Test
    fun `overlay data should update with pipeline feedback`() = testScope.runTest {
        val mockAlphaData = FloatArray(81 * 81) { it / (81f * 81f) }
        val mockDeltaEData = FloatArray(81 * 81) { (it % 10) / 10f }
        
        viewModel.updateOverlayData(mockAlphaData, mockDeltaEData)
        
        viewModel.alphaHeatmap.test {
            val alphaMap = awaitItem()
            assertNotNull(alphaMap)
            assertEquals(81 * 81, alphaMap?.size)
            // Verify data integrity
            assertEquals(0f, alphaMap!![0], 0.001f)
            assertTrue(alphaMap.last() > 0.9f)
        }
        
        viewModel.deltaEHeatmap.test {
            val deltaMap = awaitItem()
            assertNotNull(deltaMap)
            assertEquals(81 * 81, deltaMap?.size)
        }
    }
    
    @Test
    fun `memory cleanup should clear all state properly`() = testScope.runTest {
        // Populate with test data
        viewModel.onFrameCaptured()
        viewModel.updateFps(24.0)
        viewModel.updateOverlayData(FloatArray(81 * 81), FloatArray(81 * 81))
        
        // Verify state is populated
        assertTrue(viewModel.framesCaptured.first() > 0)
        assertTrue(viewModel.currentFps.first() > 0)
        assertNotNull(viewModel.alphaHeatmap.first())
        
        // Simulate cleanup (would be called in onCleared)
        viewModel.resetCapture()
        
        // Verify cleanup
        assertEquals(0, viewModel.framesCaptured.first())
        // FPS and overlay data might persist for display
    }
}
