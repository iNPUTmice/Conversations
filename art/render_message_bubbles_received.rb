#!/bin/env ruby

gem 'libxml-ruby'

require 'xml'


resolutions = {
	'mdpi' => 1,
	'hdpi' => 1.5,
	'xhdpi' => 2,
	'xxhdpi' => 3,
	'xxxhdpi' => 4,
	}

images = {
	'message_bubble_received_white.svg' => ['message_bubble_received_white.9', 0],
    'message_bubble_received_outline.svg' => ['message_bubble_received_outline.9', 0],
	}

# Executable paths for Mac OSX
# "/Applications/Inkscape.app/Contents/Resources/bin/inkscape"

inkscape = "inkscape"
imagemagick = "convert"

def execute_cmd(cmd)
	puts cmd
	system cmd
end

images.each do |source_filename, settings|
	svg_content = File.read(source_filename)

	svg = XML::Document.string(svg_content)
	base_width = svg.root["width"].to_i
	base_height = svg.root["height"].to_i

	guides = svg.find(".//sodipodi:guide","sodipodi:http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd")

	resolutions.each do |resolution, factor|
		output_filename, base_size = settings

		if base_size > 0
			width = factor * base_size
			height = factor * base_size
		else
			width = factor * base_width
			height = factor * base_height
		end

        output_parts = output_filename.split('/')

        if output_parts.count != 2
    		path = "../src/main/res/drawable-#{resolution}/#{output_filename}.png"
        else
            path = "../src/#{output_parts[0]}/res/drawable-#{resolution}/#{output_parts[1]}.png"
        end
		execute_cmd "#{inkscape} #{source_filename} -C -w #{width.to_i} -h #{height.to_i} -o #{path}"

		top = []
		right = []
		bottom = []
		left = []

		guides.each do |guide|
			orientation = guide["orientation"]
			x, y = guide["position"].split(",")
			x, y = x.to_i, y.to_i

			if orientation == "1,0" and y == base_height
				top.push(x * factor)
			end

			if orientation == "0,1" and x == base_width
				right.push((base_height - y) * factor)
			end

			if orientation == "1,0" and y == 0
				bottom.push(x * factor)
			end

			if orientation == "0,1" and x == 0
				left.push((base_height - y) * factor)
			end
		end

#		next if top.length != 2
#		next if right.length != 2
#		next if bottom.length != 2
#		next if left.length != 2


		execute_cmd "#{imagemagick} -background none PNG32:#{path} -gravity center -extent #{width+2}x#{height+2} PNG32:#{path}"

		draw_format = "-draw \"line %d,%d %d,%d\""
		top_line = draw_format % [21 * factor, 0, 24 * factor, 0]
		right_line = draw_format % [width + 1, 5 * factor, width + 1, 20 * factor]
		bottom_line = draw_format % [19 * factor, height + 1, 26 * factor, height + 1]
		left_line = draw_format % [0, 17 * factor, 0, 18 * factor]
		draws = "#{top_line} #{right_line} #{bottom_line} #{left_line}"

		execute_cmd "#{imagemagick} -background none PNG32:#{path} -fill black -stroke none #{draws} PNG32:#{path}"
	end
end
