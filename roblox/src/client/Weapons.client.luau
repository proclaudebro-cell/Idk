local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local UserInputService = game:GetService("UserInputService")
local TweenService = game:GetService("TweenService")
local Debris = game:GetService("Debris")
local Workspace = game:GetService("Workspace")

local player = Players.LocalPlayer
local camera = Workspace.CurrentCamera
local Remotes = ReplicatedStorage:WaitForChild("Remotes")
local Config = require(ReplicatedStorage:WaitForChild("Shared"):WaitForChild("Config"))

player.CameraMinZoomDistance = 9
player.CameraMaxZoomDistance = 9

local holding = false
local lastFire = 0
local recoil = 0 -- degrees, decays every frame
local shake = 0

local function equippedTool()
	local char = player.Character
	return char and char:FindFirstChildOfClass("Tool")
end

local function tryFire()
	if player:GetAttribute("InCutscene") then
		return
	end
	local char = player.Character
	local head = char and char:FindFirstChild("Head")
	local tool = equippedTool()
	local cfg = tool and Config.Weapons[tool:GetAttribute("WeaponName") or ""]
	if not head or not cfg or tool:GetAttribute("Reloading") then
		return
	end
	if (tool:GetAttribute("Ammo") or 0) <= 0 then
		return
	end
	local now = os.clock()
	if now - lastFire < cfg.FireDelay then
		return
	end
	lastFire = now

	local params = RaycastParams.new()
	params.FilterType = Enum.RaycastFilterType.Exclude
	params.FilterDescendantsInstances = { char }
	local size = camera.ViewportSize
	local ray = camera:ViewportPointToRay(size.X / 2, size.Y / 2)
	local result = Workspace:Raycast(ray.Origin, ray.Direction * cfg.Range, params)
	local target = result and result.Position or ray.Origin + ray.Direction * cfg.Range

	Remotes.Fire:FireServer(tool, (target - head.Position).Unit)
	recoil += cfg.Recoil
end

UserInputService.InputBegan:Connect(function(input, processed)
	if processed then
		return
	end
	if input.UserInputType == Enum.UserInputType.MouseButton1 then
		holding = true
		tryFire()
	elseif input.KeyCode == Enum.KeyCode.R then
		local tool = equippedTool()
		if tool then
			Remotes.Reload:FireServer(tool)
		end
	end
end)

UserInputService.InputEnded:Connect(function(input)
	if input.UserInputType == Enum.UserInputType.MouseButton1 then
		holding = false
	end
end)

-- Over-the-shoulder shooter camera + recoil/shake overlay.
RunService:BindToRenderStep("ShooterCamera", Enum.RenderPriority.Camera.Value + 1, function(dt)
	local char = player.Character
	local hum = char and char:FindFirstChildOfClass("Humanoid")
	local root = char and char:FindFirstChild("HumanoidRootPart")

	if player:GetAttribute("InCutscene") then
		UserInputService.MouseBehavior = Enum.MouseBehavior.Default
		if hum then
			hum.AutoRotate = true
			hum.CameraOffset = Vector3.zero
		end
		return
	end

	if hum and root and hum.Health > 0 then
		UserInputService.MouseBehavior = Enum.MouseBehavior.LockCenter
		hum.AutoRotate = false
		hum.CameraOffset = Vector3.new(2.2, 0.5, 0)
		local look = camera.CFrame.LookVector
		root.CFrame = CFrame.lookAt(root.Position, root.Position + Vector3.new(look.X, 0, look.Z))

		local tool = equippedTool()
		local cfg = tool and Config.Weapons[tool:GetAttribute("WeaponName") or ""]
		if holding and cfg and cfg.Auto then
			tryFire()
		end
	else
		UserInputService.MouseBehavior = Enum.MouseBehavior.Default
	end

	recoil *= math.exp(-10 * dt)
	shake = math.max(0, shake - dt * 1.5)
	local s = shake * 0.02
	camera.CFrame = camera.CFrame
		* CFrame.Angles(math.rad(recoil) + (math.random() - 0.5) * s, (math.random() - 0.5) * s, 0)
end)

Remotes.Shake.OnClientEvent:Connect(function(intensity)
	shake = math.max(shake, intensity)
end)

-- Tracers, muzzle flash and impact sparks (all triggered by the server).
local function impact(position)
	local p = Instance.new("Part")
	p.Anchored = true
	p.CanCollide = false
	p.CanQuery = false
	p.Transparency = 1
	p.Size = Vector3.one * 0.2
	p.Position = position
	local e = Instance.new("ParticleEmitter")
	e.Texture = "rbxasset://textures/particles/sparkles_main.dds"
	e.Color = ColorSequence.new(Color3.fromRGB(255, 220, 160))
	e.Lifetime = NumberRange.new(0.2, 0.4)
	e.Speed = NumberRange.new(8, 16)
	e.SpreadAngle = Vector2.new(180, 180)
	e.Size = NumberSequence.new(0.5, 0)
	e.LightEmission = 1
	e.Rate = 0
	e.Parent = p
	p.Parent = Workspace
	e:Emit(6)
	Debris:AddItem(p, 0.6)
end

Remotes.Tracer.OnClientEvent:Connect(function(origin, endpoints, hits, weaponName)
	local cfg = Config.Weapons[weaponName]
	if not cfg then
		return
	end

	local flash = Instance.new("Part")
	flash.Anchored = true
	flash.CanCollide = false
	flash.CanQuery = false
	flash.Shape = Enum.PartType.Ball
	flash.Size = Vector3.one * 0.9
	flash.Position = origin
	flash.Material = Enum.Material.Neon
	flash.Color = cfg.Tracer
	local light = Instance.new("PointLight")
	light.Range = 14
	light.Brightness = 4
	light.Color = cfg.Tracer
	light.Parent = flash
	flash.Parent = Workspace
	TweenService:Create(flash, TweenInfo.new(0.08), { Transparency = 1, Size = Vector3.one * 0.2 }):Play()
	Debris:AddItem(flash, 0.12)

	for i, finish in endpoints do
		local dist = (finish - origin).Magnitude
		local beam = Instance.new("Part")
		beam.Anchored = true
		beam.CanCollide = false
		beam.CanQuery = false
		beam.CanTouch = false
		beam.Material = Enum.Material.Neon
		beam.Color = cfg.Tracer
		beam.Size = Vector3.new(0.08, 0.08, dist)
		beam.CFrame = CFrame.lookAt(origin, finish) * CFrame.new(0, 0, -dist / 2)
		beam.Parent = Workspace
		TweenService:Create(beam, TweenInfo.new(0.12), { Transparency = 1, Size = Vector3.new(0.02, 0.02, dist) }):Play()
		Debris:AddItem(beam, 0.2)
		if hits[i] then
			impact(finish)
		end
	end
end)
