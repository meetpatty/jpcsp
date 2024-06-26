/*
This file is part of jpcsp.

Jpcsp is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Jpcsp is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp.graphics.RE;

import java.util.HashMap;

import jpcsp.graphics.RE.ShaderProgram.ShaderProgramKey;

/**
 * @author gid15
 *
 */
public class ShaderProgramManager {
	private HashMap<ShaderProgramKey, ShaderProgram> shaderPrograms;

	public ShaderProgramManager() {
		shaderPrograms = new HashMap<ShaderProgramKey, ShaderProgram>();
	}

	public ShaderProgram getShaderProgram(ShaderContext shaderContext, boolean hasGeometryShader, boolean hasTessellationShader) {
		ShaderProgramKey key = ShaderProgram.getKey(shaderContext, hasGeometryShader, hasTessellationShader);
		ShaderProgram shaderProgram = shaderPrograms.get(key);
		if (shaderProgram == null) {
			shaderProgram = new ShaderProgram(shaderContext, hasGeometryShader, hasTessellationShader);
			shaderPrograms.put(key, shaderProgram);
		}

		return shaderProgram;
	}
}
